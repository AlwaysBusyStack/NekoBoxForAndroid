//go:build !android

package libcore

func extractAssets() {}

func resetPanelAssets() error {
	return nil
}
