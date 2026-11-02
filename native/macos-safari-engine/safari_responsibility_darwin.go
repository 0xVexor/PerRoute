//go:build darwin && cgo

package process

/*
#cgo CFLAGS: -mmacosx-version-min=13.0
#cgo LDFLAGS: -framework Security -framework CoreFoundation
#include <stdlib.h>
#include "ResponsibleApplication.h"
*/
import "C"

import (
	"encoding/binary"
	"net/netip"
	"unsafe"

	"github.com/sagernet/sing-box/adapter"
)

const SafariResponsibilityAvailable = true

// Keep the original socket owner's metadata intact. This fresh lookup is only
// used by the explicit Safari rule, never by ordinary process_path matching.
func MatchSafariResponsibleConnection(selected string, metadata *adapter.InboundContext) bool {
	if metadata == nil || metadata.Network != "tcp" || metadata.ProcessInfo == nil || metadata.ProcessInfo.UserId <= 0 {
		return false
	}
	source, destination := metadata.Source.Addr.Unmap(), metadata.Destination.Addr.Unmap()
	if !source.Is4() || !destination.Is4() || metadata.Source.Port == 0 || metadata.Destination.Port == 0 {
		return false
	}
	// Bypass the engine's shared 200 ms owner snapshot. The native reader also
	// revalidates the exact tuple, UID, process lifetime and socket generation.
	owner, err := newDarwinConnectionFinder(0).find("tcp",
		netip.AddrPortFrom(source, metadata.Source.Port), netip.AddrPortFrom(destination, metadata.Destination.Port))
	if err != nil || owner.ProcessID < 2 || owner.UserId != metadata.ProcessInfo.UserId {
		stage := C.CString("fresh-owner-unavailable")
		C.PRSafariDiagnostic(stage, false, 0, C.uid_t(metadata.ProcessInfo.UserId))
		C.free(unsafe.Pointer(stage))
		return false
	}
	path := C.CString(selected)
	defer C.free(unsafe.Pointer(path))
	connection := C.PRIPv4TCPConnection{
		sourceAddress:      C.uint32_t(binary.NativeEndian.Uint32(source.AsSlice())),
		destinationAddress: C.uint32_t(binary.NativeEndian.Uint32(destination.AsSlice())),
		sourcePort:         C.uint16_t(metadata.Source.Port), destinationPort: C.uint16_t(metadata.Destination.Port),
	}
	var result C.PRResponsibleApplication
	matched := C.PRObserveSafariTCPConnection(C.pid_t(owner.ProcessID), C.uid_t(owner.UserId), path, &connection, &result)
	stage := C.CString("connection-responsibility-signature")
	C.PRSafariDiagnostic(stage, matched, C.pid_t(owner.ProcessID), C.uid_t(owner.UserId))
	C.free(unsafe.Pointer(stage))
	return bool(matched)
}
