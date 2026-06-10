package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"net"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	dialer.DoNotSelectInterface = true
}

var mainInstance *BoxInstance

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if system {
		log.Println("Reset system connections done")
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	ctx    context.Context
	cancel context.CancelFunc
	state  int

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	return newSingBoxInstance(config, localTransport, false)
}

func newSingBoxInstance(config string, localTransport LocalDNSTransport, forTest bool) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	ctx = filemanager.WithDefault(ctx, workingPath, tempPath, os.Getuid(), os.Getgid())
	service.MustRegister[adapter.PlatformInterface](ctx, &boxPlatformInterfaceWrapper{
		forTest: forTest,
	})

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %w", err)
	}
	err = validateByeDPIOptions(options)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("validate config: %w", err)
	}

	// create box
	var instance *box.Box
	func() {
		endV2GeoCacheScope := beginV2GeoCacheScope()
		defer func() {
			if endV2GeoCacheScope() {
				debug.FreeOSMemory()
			}
		}()
		instance, err = box.New(box.Options{
			Options:           options,
			Context:           ctx,
			PlatformLogWriter: boxPlatformLogWriter,
		})
	}()
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %w", err)
	}

	b = &BoxInstance{
		Box:          instance,
		ctx:          ctx,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) urlTest(tag, link string, timeout int32) (latency int32, err error) {
	var detour adapter.Outbound
	if tag == "" {
		detour = b.Outbound().Default()
	} else {
		var loaded bool
		detour, loaded = b.Outbound().Outbound(tag)
		if !loaded {
			return -1, fmt.Errorf("%s is not found", tag)
		}
	}

	ctx, cancel := context.WithTimeout(b.ctx, time.Duration(timeout)*time.Millisecond)
	defer cancel()

	type urlTestResult struct {
		latency uint16
		err     error
	}
	chLatency := make(chan urlTestResult, 1)
	go func() {
		t, testErr := urltest.URLTest(ctx, link, detour)
		chLatency <- urlTestResult{latency: t, err: testErr}
	}()

	select {
	case <-ctx.Done():
		return -1, ctx.Err()
	case result := <-chLatency:
		if result.err != nil {
			return -1, result.err
		}
		return int32(result.latency), nil
	}
}

func NewInstanceURLTest(config, tag, link string, timeout int32, standard int32, localTransport LocalDNSTransport) (latency int32, err error) {
	defer device.DeferPanicToError("NewInstanceURLTest", func(err_ error) { err = err_ })

	instance, err := newSingBoxInstance(config, localTransport, true)
	if err != nil {
		return -1, fmt.Errorf("create service: %w", err)
	}
	defer func() {
		err = errors.Join(err, instance.Close())
	}()

	acquireProtect()
	defer releaseProtect()

	err = instance.Start()
	if err != nil {
		return -1, fmt.Errorf("start service: %w", err)
	}
	if tag != "" {
		return instance.urlTest(tag, link, timeout)
	}
	return urlTest(instance, link, timeout, standard, false)
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	return b.CloseTimeout(int64(constant.FatalStopTimeout / time.Millisecond))
}

func (b *BoxInstance) CloseTimeout(timeoutMillis int64) (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// clear main instance
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		timeout := time.Duration(timeoutMillis) * time.Millisecond
		if timeout <= 0 {
			timeout = constant.FatalStopTimeout
		}
		done := make(chan error, 1)
		go func() {
			var closeErr error
			defer func() {
				if r := recover(); r != nil {
					closeErr = fmt.Errorf("box.Close goroutine panic: %s\n%s", r, string(debug.Stack()))
				}
				done <- closeErr
			}()
			closeErr = b.Box.Close()
		}()
		select {
		case err = <-done:
			return err
		case <-time.After(timeout):
			return errors.New("sing-box did not close in time")
		}
	}

	return nil
}

func (b *BoxInstance) ResetNetwork() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.ResetNetwork", func(err_ error) { err = err_ })

	if b.state != 1 || b.Box == nil {
		return nil
	}
	b.Box.Router().ResetNetwork()
	log.Println("Reset network done")
	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	return b.SelectOutboundInGroup("proxy", tag)
}

func (b *BoxInstance) SelectOutboundInGroup(groupTag string, outboundTag string) bool {
	outbound, loaded := b.Outbound().Outbound(groupTag)
	if !loaded {
		return false
	}
	selector, isSelector := outbound.(*group.Selector)
	if !isSelector {
		return false
	}
	return selector.SelectOutbound(outboundTag)
}

func UrlTest(i *BoxInstance, link string, timeout int32, standard int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	return urlTest(i, link, timeout, standard, true)
}

func urlTest(i *BoxInstance, link string, timeout int32, standard int32, enforceInstanceMinTimeout bool) (latency int32, err error) {
	var connectionTracker adapter.ConnectionTracker
	testStandard := urlTestStandard(standard)
	// test i
	if i != nil {
		if i.v2api != nil {
			connectionTracker = i.v2api.StatsService()
		}
		if enforceInstanceMinTimeout && i != mainInstance {
			if timeout < 10000 {
				timeout = 10000
			}
		}
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(i.Box, connectionTracker), link, timeout, testStandard)
	}
	// test direct
	if mainInstance == nil {
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(nil, nil), link, timeout, testStandard)
	}
	// test mainInstance
	if mainInstance.v2api != nil {
		connectionTracker = mainInstance.v2api.StatsService()
	}
	return speedtest.UrlTest(boxapi.CreateProxyHttpClient(mainInstance.Box, connectionTracker), link, timeout, testStandard)
}

func urlTestStandard(standard int32) int {
	switch standard {
	case speedtest.UrlTestStandard_Handshake:
		return speedtest.UrlTestStandard_Handshake
	case speedtest.UrlTestStandard_FisrtHandshake:
		return speedtest.UrlTestStandard_FisrtHandshake
	default:
		return speedtest.UrlTestStandard_RTT
	}
}

var (
	protectAccess sync.Mutex
	protectCloser io.Closer
	protectMain   bool
	protectUsers  int
)

func ensureProtectLocked() {
	if protectCloser != nil {
		return
	}
	if !isBgProcess && protectServerAvailable() {
		return
	}
	protectCloser = protect_server.ServeProtect(protectPath, false, 0, func(fd int) {
		if err := intfBox.AutoDetectInterfaceControl(int32(fd)); err != nil {
			log.Println("protect fd:", err)
		}
	})
}

func protectServerAvailable() bool {
	netDialer := net.Dialer{Timeout: 100 * time.Millisecond}
	conn, err := netDialer.DialContext(context.Background(), "unix", protectPath)
	if err != nil {
		return false
	}
	if err := conn.Close(); err != nil {
		return false
	}
	return true
}

func closeProtectLocked() {
	if protectCloser == nil {
		return
	}
	if err := protectCloser.Close(); err != nil {
		log.Println("close protect server:", err)
	}
	protectCloser = nil
}

func acquireProtect() {
	protectAccess.Lock()
	defer protectAccess.Unlock()
	protectUsers++
	ensureProtectLocked()
}

func releaseProtect() {
	protectAccess.Lock()
	defer protectAccess.Unlock()
	if protectUsers > 0 {
		protectUsers--
	}
	if protectUsers == 0 && !protectMain {
		closeProtectLocked()
	}
}

func goServeProtect(start bool) {
	protectAccess.Lock()
	defer protectAccess.Unlock()
	protectMain = start
	if start {
		ensureProtectLocked()
		return
	}
	if protectUsers == 0 {
		closeProtectLocked()
	}
}
