package libcore

import (
	"fmt"
	"path/filepath"
	"strings"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geosite struct {
	geositeReader *geosites.Reader
	datEntries    map[string]*v2geoSite
}

func (g *geosite) Open(path string) error {
	geositeReader, _, err := geosites.Open(path)
	if err == nil {
		g.geositeReader = geositeReader
		return nil
	}
	datEntries, _, datErr := loadV2GeoSite(path)
	if datErr != nil {
		return fmt.Errorf("open geosite as db: %w; open geosite as dat: %w", err, datErr)
	}
	g.datEntries = datEntries
	return nil
}

func (g *geosite) Rules(code string) ([]option.HeadlessRule, error) {
	if g.datEntries != nil {
		return g.datRules(code)
	}
	sourceSet, err := g.geositeReader.Read(code)
	if err != nil {
		return nil, fmt.Errorf("failed to read geosite code %s :%w", code, err)
	}

	var headlessRule option.DefaultHeadlessRule

	defaultRule := geosites.Compile(sourceSet)

	headlessRule.Domain = defaultRule.Domain
	headlessRule.DomainSuffix = defaultRule.DomainSuffix
	headlessRule.DomainKeyword = defaultRule.DomainKeyword
	headlessRule.DomainRegex = defaultRule.DomainRegex

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func (g *geosite) datRules(code string) ([]option.HeadlessRule, error) {
	code = strings.ToLower(strings.TrimSpace(code))
	entry := g.datEntries[code]
	if entry == nil {
		return nil, fmt.Errorf("failed to read geosite code %s :code not exists", code)
	}

	var headlessRule option.DefaultHeadlessRule
	for _, domain := range entry.Domain {
		if domain == nil || domain.Value == "" {
			continue
		}
		switch domain.Type {
		case v2geoDomainPlain:
			headlessRule.DomainKeyword = append(headlessRule.DomainKeyword, domain.Value)
		case v2geoDomainRegex:
			headlessRule.DomainRegex = append(headlessRule.DomainRegex, domain.Value)
		case v2geoDomainRootDomain:
			headlessRule.Domain = append(headlessRule.Domain, domain.Value)
			headlessRule.DomainSuffix = append(headlessRule.DomainSuffix, "."+domain.Value)
		case v2geoDomainFull:
			headlessRule.Domain = append(headlessRule.Domain, domain.Value)
		default:
			return nil, fmt.Errorf("unsupported geosite domain type %d for code %s", domain.Type, code)
		}
	}

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func init() {
	nekoutils.GetGeoSiteHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		g := new(geosite)
		if err := g.Open(filepath.Join(externalAssetsPath, "geosite.db")); err != nil {
			return nil, err
		}
		return g.Rules(name)
	}
}
