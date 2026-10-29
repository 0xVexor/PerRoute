package rule

import (
	"fmt"
	"path"
	"strings"
	"unicode"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/process"
)

var _ RuleItem = (*SafariResponsibleItem)(nil)

type SafariResponsibleItem struct {
	executable string
	match      func(string, *adapter.InboundContext) bool
}

func NewSafariResponsibleItem(executable string) (*SafariResponsibleItem, error) {
	if !process.SafariResponsibilityAvailable {
		return nil, fmt.Errorf("Safari responsibility requires a Darwin cgo engine")
	}
	if len(executable) == 0 || len(executable) >= 4096 || !path.IsAbs(executable) ||
		path.Clean(executable) != executable || executable == "/" || strings.Contains(executable, "\\") ||
		strings.IndexFunc(executable, unicode.IsControl) >= 0 {
		return nil, fmt.Errorf("expected an exact Safari executable path")
	}
	return &SafariResponsibleItem{executable, process.MatchSafariResponsibleConnection}, nil
}

func (r *SafariResponsibleItem) Match(metadata *adapter.InboundContext) bool {
	return r.match(r.executable, metadata)
}

func (r *SafariResponsibleItem) String() string {
	return "safari_responsible_path=" + r.executable
}
