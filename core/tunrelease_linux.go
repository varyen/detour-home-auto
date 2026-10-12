//go:build linux

package dhcore

import (
	"os"
	"strconv"

	"golang.org/x/sys/unix"
)

// releaseTun гарантирует, что движок больше не держит интерфейс туннеля: интерфейс
// VPN живёт, пока открыт хоть один его fd. Если после остановки mihomo fd всё ещё
// указывает на tun, номер подменяется на /dev/null — сам номер остаётся занятым, так
// что запоздалое закрытие со стороны mihomo не заденет чужой дескриптор.
func releaseTun(fd int32) bool {
	if fd < 0 {
		return false
	}
	if target, err := os.Readlink("/proc/self/fd/" + strconv.Itoa(int(fd))); err != nil || target != "/dev/tun" {
		return false
	}
	null, err := unix.Open("/dev/null", unix.O_RDWR|unix.O_CLOEXEC, 0)
	if err != nil {
		return false
	}
	defer unix.Close(null)
	return unix.Dup3(null, int(fd), unix.O_CLOEXEC) == nil
}
