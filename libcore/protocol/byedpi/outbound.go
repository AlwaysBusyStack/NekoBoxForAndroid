package byedpi

import (
	"context"
	"fmt"
	"net"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/protocol/socks"
)

const TypeByeDPI = "byedpi"

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[OutboundOptions](registry, TypeByeDPI, NewOutbound)
}

type Outbound struct {
	outbound.Adapter
	ctx    context.Context
	logger logger.ContextLogger
	cli    string

	startOnce sync.Once
	startErr  error
	handle    *bridgeHandle
	client    *socks.Client
	dialer    N.Dialer
	queryOpts adapter.DNSQueryOptions
}

func NewOutbound(ctx context.Context, _ adapter.Router, logger log.ContextLogger, tag string, options OutboundOptions) (adapter.Outbound, error) {
	if options.Detour != "" {
		return nil, E.New("byedpi cannot be used with detour")
	}
	queryOptions, err := adapter.DNSQueryOptionsFrom(ctx, options.DomainResolver)
	if err != nil {
		return nil, err
	}
	//nolint:staticcheck
	if options.DomainStrategy != option.DomainStrategy(C.DomainStrategyAsIS) {
		queryOptions.Strategy = C.DomainStrategy(options.DomainStrategy)
	}
	return &Outbound{
		Adapter:   outbound.NewAdapter(TypeByeDPI, tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
		ctx:       ctx,
		logger:    logger,
		cli:       options.CLI,
		queryOpts: *queryOptions,
	}, nil
}

func (o *Outbound) Close() error {
	return releaseBridge(o.handle)
}

func (o *Outbound) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	return o.ensureStarted()
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if err := o.ensureStarted(); err != nil {
		return nil, err
	}
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		o.logger.InfoContext(ctx, "outbound connection to ", destination)
		return o.dialer.DialContext(ctx, N.NetworkTCP, destination)
	case N.NetworkUDP:
		conn, err := o.ListenPacket(ctx, destination)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(conn, destination), nil
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if err := o.ensureStarted(); err != nil {
		return nil, err
	}
	o.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	return o.dialer.ListenPacket(ctx, destination)
}

func (o *Outbound) ensureStarted() error {
	o.startOnce.Do(func() {
		handle, err := acquireBridge(o.cli)
		if err != nil {
			o.startErr = err
			return
		}
		o.handle = handle
		o.client = socks.NewClient(N.SystemDialer, M.ParseSocksaddrHostPort("127.0.0.1", handle.port), socks.Version5, "", "")
		o.dialer = dialer.NewResolveDialer(o.ctx, o.client, false, "", o.queryOpts, 0)
	})
	if o.startErr != nil {
		return fmt.Errorf("start byedpi outbound %s: %w", o.Tag(), o.startErr)
	}
	return nil
}
