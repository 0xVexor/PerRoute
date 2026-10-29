//go:build !darwin || !cgo

package process

import "github.com/sagernet/sing-box/adapter"

const SafariResponsibilityAvailable = false

func MatchSafariResponsibleConnection(_ string, _ *adapter.InboundContext) bool {
	return false
}
