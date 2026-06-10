//go:build android

package libcore

import (
	"fmt"
	"io"
	"log"
	"os"
	"strconv"
	"strings"

	"github.com/sagernet/gomobile/asset"
)

const customAssetVersionPrefix = "custom:"

func extractAssets() {
	useOfficialAssets := intfNB4A.UseOfficialAssets()

	extract := func(name string) {
		err := extractAssetName(name, useOfficialAssets)
		if err != nil {
			log.Println("Extract", geoipDat, "failed:", err)
		}
	}

	extract(geoipDat)
	extract(geositeDat)
	extract(throneRulesetDat)
	extract(itdogRulesetDat)
	extract(metacubexdDstFolder)
}

func resetPanelAssets() error {
	if err := os.RemoveAll(internalAssetsPath + metacubexdDstFolder); err != nil {
		return err
	}
	if err := os.Remove(internalAssetsPath + metacubexdVersion); err != nil && !os.IsNotExist(err) {
		return err
	}
	return extractAssetName(metacubexdDstFolder, false)
}

// 这里解压的是 apk 里面的
func extractAssetName(name string, useOfficialAssets bool) error {
	// 支持非官方源的，就是 replaceable，放 Android 目录
	// 不支持非官方源的，就放 file 目录
	replaceable := true

	var version string
	var apkPrefix string
	switch name {
	case geoipDat:
		version = geoipVersion
		apkPrefix = apkAssetPrefixSingBox
	case geositeDat:
		version = geositeVersion
		apkPrefix = apkAssetPrefixSingBox
	case throneRulesetDat:
		version = throneRulesetVersion
		apkPrefix = apkAssetPrefixSingBox
	case itdogRulesetDat:
		version = itdogRulesetVersion
		apkPrefix = apkAssetPrefixSingBox
	case metacubexdDstFolder:
		version = metacubexdVersion
		replaceable = false
	}

	var dir string
	if !replaceable {
		dir = internalAssetsPath
	} else {
		dir = externalAssetsPath
	}
	dstName := dir + name

	var localVersion string
	var assetVersion string

	// loadAssetVersion from APK
	loadAssetVersion := func() error {
		av, err := asset.Open(apkPrefix + version)
		if err != nil {
			return fmt.Errorf("open version in assets: %v", err)
		}
		b, err := io.ReadAll(av)
		av.Close()
		if err != nil {
			return fmt.Errorf("read internal version: %v", err)
		}
		assetVersion = string(b)
		return nil
	}
	if err := loadAssetVersion(); err != nil {
		return err
	}

	var doExtract bool

	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if useOfficialAssets || !replaceable {
		// 官方源升级
		b, err := os.ReadFile(dir + version)
		if err != nil {
			// versionFileMissing
			doExtract = true
			_ = os.RemoveAll(version)
		} else {
			localVersion = string(b)
			if strings.HasPrefix(localVersion, customAssetVersionPrefix) {
				doExtract = false
			} else {
				av, err := strconv.ParseUint(assetVersion, 10, 64)
				if err != nil {
					doExtract = assetVersion != localVersion
				} else {
					lv, err := strconv.ParseUint(localVersion, 10, 64)
					doExtract = err != nil || av > lv
				}
			}
		}
	} else {
		//非官方源不升级
	}

	if !doExtract {
		return nil
	}

	extractXz := func(f asset.File) error {
		tmpXzName := dstName + ".xz"
		err := extractAsset(f, tmpXzName)
		if err == nil {
			err = Unxz(tmpXzName, dstName)
			os.Remove(tmpXzName)
		}
		if err != nil {
			return fmt.Errorf("extract xz: %v", err)
		}
		return nil
	}

	extractTarGz := func(f asset.File, outDir string) error {
		tmpTarGzName := dstName + ".tgz"
		err := extractAsset(f, tmpTarGzName)
		if err == nil {
			err = UntarGz(tmpTarGzName, outDir)
			os.Remove(tmpTarGzName)
		}
		if err != nil {
			return fmt.Errorf("extract tgz: %v", err)
		}
		return nil
	}

	if f, err := asset.Open(apkPrefix + name + ".xz"); err == nil {
		extractXz(f)
	} else if f, err := asset.Open(apkPrefix + name); err == nil {
		err = extractAsset(f, dstName)
		if err != nil {
			return fmt.Errorf("extract asset: %v", err)
		}
	} else if f, err := asset.Open("metacubexd.tgz"); err == nil {
		os.RemoveAll(dstName)
		if err := os.MkdirAll(dstName, 0o755); err != nil {
			return fmt.Errorf("mkdir metacubexd dir: %v", err)
		}
		if err := extractTarGz(f, dstName); err != nil {
			return err
		}
	} else {
		return fmt.Errorf("asset not found: %s", apkPrefix+name)
	}

	o, err := os.Create(dir + version)
	if err != nil {
		return fmt.Errorf("create version: %v", err)
	}
	_, err = io.WriteString(o, assetVersion)
	o.Close()
	return err
}

func extractAsset(i asset.File, path string) error {
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	defer o.Close()
	_, err = io.Copy(o, i)
	if err == nil {
		log.Println("Extract >>", path)
	}
	return err
}
