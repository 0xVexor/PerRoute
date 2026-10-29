package rule

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/process"
	"github.com/sagernet/sing-box/option"
)

func TestSafariResponsibleScope(t *testing.T) {
	if !process.SafariResponsibilityAvailable {
		if _, err := NewSafariResponsibleItem("/Applications/Safari.app/Contents/MacOS/Safari"); err == nil {
			t.Fatal("unsupported engines must reject the rule")
		}
		return
	}
	for _, invalid := range []string{"", "/", "Safari", "/Applications/../Safari", "/Safari/", "/Safari\n", "/Safari\\helper"} {
		if _, err := NewSafariResponsibleItem(invalid); err == nil {
			t.Fatalf("accepted invalid path %q", invalid)
		}
	}
	item, err := NewSafariResponsibleItem("/Applications/Safari.app/Contents/MacOS/Safari")
	if err != nil {
		t.Fatal(err)
	}
	metadata := &adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{ProcessPath: "/System/WebKit/Networking"}}
	item.match = func(selected string, observed *adapter.InboundContext) bool {
		return selected == item.executable && observed == metadata
	}
	if !item.Match(metadata) {
		t.Fatal("explicit matcher was not used")
	}
	if metadata.ProcessInfo.ProcessPath != "/System/WebKit/Networking" {
		t.Fatal("socket identity was rewritten")
	}
	if NewProcessPathItem([]string{item.executable}).Match(metadata) {
		t.Fatal("ordinary executable scope was broadened")
	}
	if !NewProcessPathItem([]string{"/System/WebKit/Networking"}).Match(metadata) {
		t.Fatal("ordinary exact matching changed")
	}
}

func TestSafariResponsibleJSONRule(t *testing.T) {
	var options option.DefaultRule
	if err := json.Unmarshal([]byte(`{"safari_responsible_path":"/Applications/Safari.app/Contents/MacOS/Safari","network":"tcp","user_id":[501],"action":"route","outbound":"selected"}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.SafariResponsiblePath != "/Applications/Safari.app/Contents/MacOS/Safari" || !options.IsValid() {
		t.Fatal("explicit Safari condition was not decoded")
	}
	rule, err := NewDefaultRule(context.Background(), nil, options)
	if !process.SafariResponsibilityAvailable {
		if err == nil {
			t.Fatal("unsupported engine accepted the rule")
		}
		return
	}
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, item := range rule.items {
		if _, ok := item.(*SafariResponsibleItem); ok {
			found = true
		}
	}
	if !found {
		t.Fatal("decoded Safari rule did not install the responsible-application matcher")
	}
}

func TestSafariResponsibleRejectsMissingFlow(t *testing.T) {
	for _, metadata := range []*adapter.InboundContext{nil, {}, {Network: "udp"},
		{Network: "tcp", ProcessInfo: &adapter.ConnectionOwner{UserId: 0}}} {
		if process.MatchSafariResponsibleConnection("/Applications/Safari.app/Contents/MacOS/Safari", metadata) {
			t.Fatal("matched unavailable ownership or unsupported traffic")
		}
	}
}
