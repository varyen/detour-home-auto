//go:build !linux

package dhcore

// На iOS fd туннеля принадлежит NetworkExtension — трогать его нельзя.
func releaseTun(fd int32) bool { return false }
