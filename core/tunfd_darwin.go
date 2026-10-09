//go:build darwin

package dhcore

import (
	"strings"

	"golang.org/x/sys/unix"
)

// TunnelFileDescriptor ищет fd utun, который NetworkExtension открыл расширению
// после setTunnelNetworkSettings: перебор дескрипторов и UTUN_OPT_IFNAME.
func TunnelFileDescriptor() int32 {
	const sysprotoControl, utunOptIfname = 2, 2
	for fd := 0; fd < 1024; fd++ {
		name, err := unix.GetsockoptString(fd, sysprotoControl, utunOptIfname)
		if err == nil && strings.HasPrefix(name, "utun") {
			return int32(fd)
		}
	}
	return -1
}
