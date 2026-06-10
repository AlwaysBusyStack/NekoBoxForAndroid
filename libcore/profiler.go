package libcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"runtime/pprof"
	"runtime/trace"
	"strings"
	"sync"
	"time"
)

var coreProfiler = &profilerState{}

type profilerState struct {
	access sync.Mutex

	running bool
	started time.Time

	cpuFile   *os.File
	traceFile *os.File
	dir       string
}

func CoreProfilingRunning() bool {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()
	return coreProfiler.running
}

func HasCoreProfilerSnapshot() bool {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()
	if coreProfiler.running {
		return true
	}
	return profilerFileExists(coreProfiler.dir, "cpu.pprof") ||
		profilerFileExists(coreProfiler.dir, "trace.out")
}

func StartCoreProfiling() (err error) {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()

	if coreProfiler.running {
		return nil
	}
	if mainInstance == nil || mainInstance.state != 1 {
		return errors.New("Core is not started yet")
	}
	if tempPath == "" {
		return errors.New("core is not initialized")
	}

	dir := filepath.Join(tempPath, "core-profiler")
	err = os.RemoveAll(dir)
	if err != nil {
		return err
	}
	err = os.MkdirAll(dir, 0700)
	if err != nil {
		return err
	}

	cpuFile, err := os.Create(filepath.Join(dir, "cpu.pprof"))
	if err != nil {
		return err
	}
	defer func() {
		if err != nil {
			err = errors.Join(err, cpuFile.Close())
		}
	}()
	err = pprof.StartCPUProfile(cpuFile)
	if err != nil {
		return err
	}

	traceFile, err := os.Create(filepath.Join(dir, "trace.out"))
	if err != nil {
		pprof.StopCPUProfile()
		return err
	}
	defer func() {
		if err != nil {
			err = errors.Join(err, traceFile.Close())
		}
	}()
	err = trace.Start(traceFile)
	if err != nil {
		pprof.StopCPUProfile()
		return err
	}

	runtime.SetBlockProfileRate(1)
	runtime.SetMutexProfileFraction(1)

	coreProfiler.running = true
	coreProfiler.started = time.Now()
	coreProfiler.cpuFile = cpuFile
	coreProfiler.traceFile = traceFile
	coreProfiler.dir = dir
	return nil
}

func StopCoreProfiling() (err error) {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()
	return coreProfiler.stopLocked()
}

func WriteCoreProfilerSnapshot(outputDir string) (err error) {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()

	if coreProfiler.running {
		err = coreProfiler.stopLocked()
		if err != nil {
			return err
		}
	}
	if coreProfiler.dir == "" || !HasCoreProfilerSnapshotLocked() {
		return errors.New("no profiler snapshot has been collected yet")
	}
	err = os.MkdirAll(outputDir, 0700)
	if err != nil {
		return err
	}

	err = writeRuntimeProfiles(outputDir)
	if err != nil {
		return err
	}
	err = writeProfilerMetadata(outputDir, coreProfiler.started)
	if err != nil {
		return err
	}
	return copyProfilerFiles(coreProfiler.dir, outputDir)
}

func DeleteCoreProfilerSnapshot() (err error) {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()

	if coreProfiler.running {
		err = coreProfiler.stopLocked()
		if err != nil {
			return err
		}
	}
	if coreProfiler.dir != "" {
		err = os.RemoveAll(coreProfiler.dir)
	}
	coreProfiler.dir = ""
	coreProfiler.started = time.Time{}
	return err
}

func (p *profilerState) stopLocked() error {
	if !p.running {
		return nil
	}

	trace.Stop()
	pprof.StopCPUProfile()
	runtime.SetBlockProfileRate(0)
	runtime.SetMutexProfileFraction(0)

	err := closeProfilerFiles(p.cpuFile, p.traceFile)
	p.cpuFile = nil
	p.traceFile = nil
	p.running = false
	return err
}

func closeProfilerFiles(files ...*os.File) error {
	var err error
	for _, file := range files {
		if file == nil {
			continue
		}
		if closeErr := file.Close(); closeErr != nil && err == nil {
			err = closeErr
		}
	}
	return err
}

func writeRuntimeProfiles(outputDir string) error {
	profiles := []string{"heap", "allocs", "goroutine", "threadcreate", "block", "mutex"}
	for _, name := range profiles {
		profile := pprof.Lookup(name)
		if profile == nil {
			continue
		}
		file, err := os.Create(filepath.Join(outputDir, name+".pprof"))
		if err != nil {
			return err
		}
		err = profile.WriteTo(file, 0)
		err = errors.Join(err, file.Close())
		if err != nil {
			return fmt.Errorf("write %s profile: %w", name, err)
		}
	}

	goroutineDebug, err := os.Create(filepath.Join(outputDir, "goroutine-debug.txt"))
	if err != nil {
		return err
	}
	err = pprof.Lookup("goroutine").WriteTo(goroutineDebug, 2)
	err = errors.Join(err, goroutineDebug.Close())
	if err != nil {
		return fmt.Errorf("write goroutine debug profile: %w", err)
	}

	return nil
}

func writeProfilerMetadata(outputDir string, started time.Time) error {
	var memStats runtime.MemStats
	runtime.ReadMemStats(&memStats)
	if err := writeJSONFile(filepath.Join(outputDir, "memstats.json"), &memStats); err != nil {
		return err
	}

	runtime.GC()
	runtime.ReadMemStats(&memStats)
	if err := writeJSONFile(filepath.Join(outputDir, "memstats-after-gc.json"), &memStats); err != nil {
		return err
	}

	err := writeProcSnapshot(outputDir)
	if err != nil {
		return err
	}
	buildInfo, loaded := debug.ReadBuildInfo()
	buildText := "build info unavailable\n"
	if loaded {
		buildText = buildInfo.String()
	}
	err = os.WriteFile(filepath.Join(outputDir, "buildinfo.txt"), []byte(buildText), 0600)
	if err != nil {
		return err
	}

	metadata := fmt.Sprintf(
		"started: %s\nstopped: %s\nduration: %s\ngo: %s\nplatform: %s/%s\ngoroutines: %d\n",
		started.Format(time.RFC3339Nano),
		time.Now().Format(time.RFC3339Nano),
		time.Since(started),
		runtime.Version(),
		runtime.GOOS,
		runtime.GOARCH,
		runtime.NumGoroutine(),
	)
	return os.WriteFile(filepath.Join(outputDir, "metadata.txt"), []byte(metadata), 0600)
}

func writeJSONFile(path string, value any) error {
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	encoder := json.NewEncoder(file)
	encoder.SetIndent("", "  ")
	err = encoder.Encode(value)
	err = errors.Join(err, file.Close())
	return err
}

func writeProcSnapshot(outputDir string) error {
	var procErrors []string
	for _, name := range []string{"status", "statm", "smaps_rollup"} {
		source := filepath.Join("/proc/self", name)
		content, readErr := os.ReadFile(source)
		if readErr != nil {
			procErrors = append(procErrors, fmt.Sprintf("read %s: %s", source, readErr))
			continue
		}
		writeErr := os.WriteFile(filepath.Join(outputDir, "proc-"+name+".txt"), content, 0600)
		if writeErr != nil {
			procErrors = append(procErrors, fmt.Sprintf("write proc-%s.txt: %s", name, writeErr))
		}
	}
	if len(procErrors) > 0 {
		return os.WriteFile(filepath.Join(outputDir, "proc-errors.txt"), []byte(strings.Join(procErrors, "\n")+"\n"), 0600)
	}
	return nil
}

func copyProfilerFiles(sourceDir string, outputDir string) error {
	files := []string{"cpu.pprof", "trace.out"}
	for _, name := range files {
		source, err := os.Open(filepath.Join(sourceDir, name))
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				continue
			}
			return err
		}
		destination, err := os.OpenFile(filepath.Join(outputDir, name), os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
		if err != nil {
			_ = source.Close()
			return err
		}
		_, copyErr := io.Copy(destination, source)
		err = errors.Join(copyErr, destination.Close(), source.Close())
		if err != nil {
			return fmt.Errorf("copy %s: %w", name, err)
		}
	}
	return nil
}

func profilerFileExists(dir string, name string) bool {
	if dir == "" {
		return false
	}
	info, err := os.Stat(filepath.Join(dir, name))
	return err == nil && info.Size() > 0
}

func HasCoreProfilerSnapshotLocked() bool {
	return profilerFileExists(coreProfiler.dir, "cpu.pprof") ||
		profilerFileExists(coreProfiler.dir, "trace.out")
}
