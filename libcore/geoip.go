package libcore

import (
	"errors"
	"fmt"
	"net"
	"path/filepath"
	"strings"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geoip struct {
	geoipReader *maxminddb.Reader
	datEntries  map[string]*v2geoIP
}

func (g *geoip) Open(path string) error {
	geoipReader, err := maxminddb.Open(path)
	if err == nil {
		g.geoipReader = geoipReader
		return nil
	}
	datEntries, _, datErr := loadV2GeoIP(path)
	if datErr != nil {
		return fmt.Errorf("open geoip as db: %w; open geoip as dat: %w", err, datErr)
	}
	g.datEntries = datEntries
	return nil
}

func (g *geoip) Rules(countryCode string) ([]option.HeadlessRule, error) {
	countryCode = strings.ToLower(strings.TrimSpace(countryCode))
	if g.datEntries != nil {
		return g.datRules(countryCode)
	}
	networks := g.geoipReader.Networks(maxminddb.SkipAliasedNetworks)
	countryMap := make(map[string][]*net.IPNet)
	var (
		ipNet           *net.IPNet
		nextCountryCode string
		err             error
	)
	for networks.Next() {
		ipNet, err = networks.Network(&nextCountryCode)
		if err != nil {
			return nil, fmt.Errorf("failed to get network: %w", err)
		}
		countryMap[nextCountryCode] = append(countryMap[nextCountryCode], ipNet)
	}

	ipNets := countryMap[countryCode]

	if len(ipNets) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}

	var headlessRule option.DefaultHeadlessRule
	headlessRule.IPCIDR = make([]string, 0, len(ipNets))
	for _, cidr := range ipNets {
		headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidr.String())
	}

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func (g *geoip) datRules(countryCode string) ([]option.HeadlessRule, error) {
	entry := g.datEntries[countryCode]
	if entry == nil || len(entry.CIDR) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}

	var headlessRule option.DefaultHeadlessRule
	headlessRule.IPCIDR = make([]string, 0, len(entry.CIDR))
	for _, cidr := range entry.CIDR {
		cidrString, err := cidrString(cidr)
		if err != nil {
			return nil, err
		}
		headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidrString)
	}

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = func(name string) (rules []option.HeadlessRule, err error) {
		g := new(geoip)
		if err := g.Open(filepath.Join(externalAssetsPath, "geoip.db")); err != nil {
			return nil, err
		}
		if g.geoipReader != nil {
			defer func() {
				err = errors.Join(err, g.geoipReader.Close())
			}()
		}
		return g.Rules(name)
	}
}
