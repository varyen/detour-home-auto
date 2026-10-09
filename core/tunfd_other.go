//go:build !darwin

package dhcore

// TunnelFileDescriptor нужен только на iOS/macOS; на Android fd отдаёт VpnService.
func TunnelFileDescriptor() int32 { return -1 }
