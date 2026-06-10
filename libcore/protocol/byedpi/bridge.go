//go:build android && cgo

package byedpi

/*
#cgo CFLAGS: -I${SRCDIR}/../../byedpi
#cgo LDFLAGS: -llog

#include <stdlib.h>

struct byedpi_runner;

struct byedpi_runner *byedpi_runner_start(int argc, char **argv);
int byedpi_runner_wait_port(struct byedpi_runner *runner, int timeout_ms);
int byedpi_runner_stop(struct byedpi_runner *runner);
int byedpi_runner_join(struct byedpi_runner *runner);
const char *byedpi_runner_last_error(struct byedpi_runner *runner);
void byedpi_runner_free(struct byedpi_runner *runner);
int byedpi_android_protect_fd(int fd);
*/
import "C"

import (
	"fmt"
	protectfd "libcore/protect"
	"slices"
	"strings"
	"sync"
	"time"
	"unsafe"

	"github.com/google/shlex"
)

//export byedpi_android_protect_fd
func byedpi_android_protect_fd(fd C.int) C.int {
	if err := protectfd.FD(int(fd)); err != nil {
		return -1
	}
	return 0
}

type bridgeHandle struct {
	runner *C.struct_byedpi_runner
	port   uint16
	key    string
	refs   int
}

var (
	bridgeAccess  sync.Mutex
	activeBridges = make(map[string]*bridgeHandle)
)

func acquireBridge(cli string) (*bridgeHandle, error) {
	key := strings.TrimSpace(cli)

	bridgeAccess.Lock()
	defer bridgeAccess.Unlock()

	if bridge, ok := activeBridges[key]; ok {
		bridge.refs++
		return bridge, nil
	}

	args, err := buildArgs(cli)
	if err != nil {
		return nil, err
	}
	cArgs, cleanup := makeCStringArray(args)
	defer cleanup()

	runner := C.byedpi_runner_start(C.int(len(args)), cArgs)
	if runner == nil {
		return nil, fmt.Errorf("start ByeDPI runner")
	}
	port := C.byedpi_runner_wait_port(runner, 5000)
	if port <= 0 {
		exitCode := int(C.byedpi_runner_join(runner))
		stderrText := strings.TrimSpace(C.GoString(C.byedpi_runner_last_error(runner)))
		C.byedpi_runner_free(runner)
		if stderrText != "" {
			return nil, fmt.Errorf("ByeDPI did not start listening: %s", stderrText)
		}
		return nil, fmt.Errorf("ByeDPI did not start listening, exit code %d", exitCode)
	}

	bridge := &bridgeHandle{
		runner: runner,
		port:   uint16(port),
		key:    key,
		refs:   1,
	}
	activeBridges[key] = bridge
	return bridge, nil
}

func releaseBridge(handle *bridgeHandle) error {
	if handle == nil {
		return nil
	}

	bridgeAccess.Lock()
	activeBridge, ok := activeBridges[handle.key]
	if !ok || activeBridge != handle {
		bridgeAccess.Unlock()
		return nil
	}
	handle.refs--
	if handle.refs > 0 {
		bridgeAccess.Unlock()
		return nil
	}
	delete(activeBridges, handle.key)
	bridgeAccess.Unlock()

	stopResult := int(C.byedpi_runner_stop(handle.runner))
	done := make(chan int, 1)
	go func() {
		done <- int(C.byedpi_runner_join(handle.runner))
	}()

	var joinResult int
	joined := false
	select {
	case joinResult = <-done:
		joined = true
	case <-time.After(3 * time.Second):
		joinResult = -1
	}
	if joined {
		C.byedpi_runner_free(handle.runner)
	}

	if stopResult != 0 && stopResult != -1 {
		return fmt.Errorf("stop ByeDPI runner: %d", stopResult)
	}
	if !joined {
		return fmt.Errorf("stop ByeDPI runner: join timed out")
	}
	if joinResult != 0 && joinResult != -1 {
		return fmt.Errorf("ByeDPI exited with code %d", joinResult)
	}
	return nil
}

func buildArgs(cli string) ([]string, error) {
	userArgs, err := shlex.Split(cli)
	if err != nil {
		return nil, fmt.Errorf("parse CLI strategy: %w", err)
	}
	userArgs = sanitizeArgs(userArgs)
	args := []string{
		"ciadpi",
		"--ip", "127.0.0.1",
		"--port", "0",
		"--protect-path", "protect_path",
	}
	return append(args, userArgs...), nil
}

func sanitizeArgs(args []string) []string {
	skipWithValue := map[string]struct{}{
		"-i":             {},
		"--ip":           {},
		"-p":             {},
		"--port":         {},
		"-P":             {},
		"--protect-path": {},
		"-w":             {},
		"--pidfile":      {},
		"-y":             {},
		"--cache-dump":   {},
		"-B":             {},
		"--copy":         {},
	}
	skipStandalone := []string{
		"-D",
		"--daemon",
		"-h",
		"--help",
		"-v",
		"--version",
	}

	filtered := make([]string, 0, len(args))
	for index := 0; index < len(args); index++ {
		arg := args[index]
		if _, ok := skipWithValue[arg]; ok {
			if index+1 < len(args) {
				index++
			}
			continue
		}
		if slices.Contains(skipStandalone, arg) {
			continue
		}
		filtered = append(filtered, arg)
	}
	return filtered
}

func makeCStringArray(args []string) (**C.char, func()) {
	cArgs := make([]*C.char, 0, len(args))
	for _, arg := range args {
		cArgs = append(cArgs, C.CString(arg))
	}
	return (**C.char)(unsafe.Pointer(&cArgs[0])), func() {
		for _, arg := range cArgs {
			C.free(unsafe.Pointer(arg))
		}
	}
}
