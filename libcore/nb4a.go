package libcore

import (
	"errors"
	"fmt"
	"libcore/device"
	"libcore/masterdnsvpnbridge"
	"libcore/protect"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	_ "unsafe"

	"log"

	"github.com/matsuridayo/libneko/neko_common"
	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
	"golang.org/x/sys/unix"
)

//go:linkname resourcePaths github.com/sagernet/sing-box/constant.resourcePaths
var resourcePaths []string
var protectPath = "protect_path"
var workingPath string
var tempPath string

func NekoLogPrintln(s string) {
	log.Println(s)
}

func NekoLogClear() {
	neko_log.LogWriter.Truncate()
}

func ForceGc() {
	go debug.FreeOSMemory()
}

func PerformLibcoreGCSweep() {
	runtime.GC()
	debug.FreeOSMemory()
}

func InitCore(process, cachePath, internalAssets, externalAssets string,
	maxLogSizeKb int32, logEnable bool,
	if1 NB4AInterface, if2 BoxPlatformInterface, if3 LocalDNSTransport,
) {
	defer device.DeferPanicToError("InitCore", func(err error) { log.Println(err) })
	isBgProcess = strings.HasSuffix(process, ":bg")

	neko_common.RunMode = neko_common.RunMode_NekoBoxForAndroid
	intfNB4A = if1
	masterdnsvpnbridge.SetReporter(func(found int32, total int32, ready bool) {
		if intfNB4A != nil {
			intfNB4A.MasterDnsVPNResolverProgress(found, total, ready)
		}
	})
	masterdnsvpnbridge.SetFailureReporter(func(noWorkingDNS bool, message string) {
		if intfNB4A != nil {
			intfNB4A.MasterDnsVPNStartupFailed(noWorkingDNS, message)
		}
	})
	intfBox = if2
	useProcfs = intfBox.UseProcFS()
	gLocalDNSTransport = newPlatformTransport(if3, "", option.LocalDNSServerOptions{})
	protect.SetProtector(func(fd int) error {
		if !isBgProcess {
			return sendFdToProtect(fd, protectPath)
		}
		return intfBox.AutoDetectInterfaceControl(int32(fd))
	})

	// Working dir
	tmp := filepath.Join(cachePath, "../no_backup")
	os.MkdirAll(tmp, 0755)
	os.Chdir(tmp)
	protectPath = filepath.Join(tmp, "protect_path")
	workingPath = tmp
	tempPath = cachePath

	// sing-box fs
	resourcePaths = append(resourcePaths, externalAssets)
	externalAssetsPath = externalAssets
	internalAssetsPath = internalAssets

	// Set up log
	if maxLogSizeKb < 50 {
		maxLogSizeKb = 50
	}
	neko_log.LogWriterDisable = !logEnable
	neko_log.TruncateOnStart = isBgProcess
	neko_log.SetupLog(int(maxLogSizeKb)*1024, filepath.Join(cachePath, "neko.log"))

	// nekoutils
	nekoutils.Selector_OnProxySelected = intfNB4A.Selector_OnProxySelected

	// Set up some component
	go func() {
		defer device.DeferPanicToError("InitCore-go", func(err error) { log.Println(err) })
		device.GoDebug(process)

		// bg
		if isBgProcess {
			extractAssets()
		}
	}()
}

func sendFdToProtect(fd int, path string) (err error) {
	if path == "" {
		path = protectPath
	}
	socketFd, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		return fmt.Errorf("failed to create unix socket: %w", err)
	}
	defer func() {
		err = errors.Join(err, unix.Close(socketFd))
	}()

	var timeout unix.Timeval
	timeout.Usec = 100 * 1000

	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &timeout)
	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_SNDTIMEO, &timeout)

	err = unix.Connect(socketFd, &unix.SockaddrUnix{Name: path})
	if err != nil {
		return fmt.Errorf("failed to connect: %w", err)
	}

	err = unix.Sendmsg(socketFd, nil, unix.UnixRights(fd), nil, 0)
	if err != nil {
		return fmt.Errorf("failed to send: %w", err)
	}

	dummy := []byte{1}
	n, err := unix.Read(socketFd, dummy)
	if err != nil {
		return fmt.Errorf("failed to receive: %w", err)
	}
	if n != 1 {
		return fmt.Errorf("socket closed unexpectedly")
	}
	return nil
}
