//go:build android && cgo

#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "../../../../byedpi/proxy.h"

int byedpi_embedded_main(int argc, char **argv);

#define BYEDPI_BRIDGE_TAG "NB4A-ByeDPI"

struct byedpi_runner {
    pthread_t thread;
    int argc;
    char **argv;
    int exit_code;
    int finished;
    int server_fd;
    int port;
    char *last_error;
    pthread_mutex_t mutex;
};

static __thread struct byedpi_runner *current_runner;

static int byedpi_port_from_fd(int server_fd) {
    if (server_fd <= 0) {
        return 0;
    }
    union sockaddr_u addr;
    socklen_t addr_len = sizeof(addr);
    if (getsockname(server_fd, &addr.sa, &addr_len) < 0) {
        return 0;
    }
    if (addr.sa.sa_family == AF_INET6) {
        return ntohs(addr.in6.sin6_port);
    }
    return ntohs(addr.in.sin_port);
}

void byedpi_android_listener_ready(int server_fd) {
    struct byedpi_runner *runner = current_runner;
    if (runner == NULL) {
        return;
    }
    int port = byedpi_port_from_fd(server_fd);
    pthread_mutex_lock(&runner->mutex);
    runner->server_fd = server_fd;
    runner->port = port;
    pthread_mutex_unlock(&runner->mutex);
    __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "listener fd=%d port=%d", server_fd, port);
}

static int byedpi_runner_copy_argv(struct byedpi_runner *runner, int argc, char **argv) {
    runner->argv = calloc((size_t)argc, sizeof(*runner->argv));
    if (runner->argv == NULL) {
        return -1;
    }
    for (int i = 0; i < argc; i++) {
        size_t length = strlen(argv[i]) + 1;
        runner->argv[i] = malloc(length);
        if (runner->argv[i] == NULL) {
            return -1;
        }
        memcpy(runner->argv[i], argv[i], length);
    }
    return 0;
}

static void byedpi_runner_free_argv(struct byedpi_runner *runner) {
    if (runner->argv == NULL) {
        return;
    }
    for (int i = 0; i < runner->argc; i++) {
        free(runner->argv[i]);
    }
    free(runner->argv);
    runner->argv = NULL;
}

static void *byedpi_runner_thread(void *arg) {
    struct byedpi_runner *runner = (struct byedpi_runner *)arg;
    current_runner = runner;
    __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "runner start argc=%d", runner->argc);
    for (int i = 0; i < runner->argc; i++) {
        __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "argv[%d]=%s", i, runner->argv[i]);
    }
    int exit_code = byedpi_embedded_main(runner->argc, runner->argv);
    current_runner = NULL;
    __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "runner exit=%d", exit_code);
    pthread_mutex_lock(&runner->mutex);
    runner->exit_code = exit_code;
    runner->finished = 1;
    pthread_mutex_unlock(&runner->mutex);
    return NULL;
}

struct byedpi_runner *byedpi_runner_start(int argc, char **argv) {
    struct byedpi_runner *runner = calloc(1, sizeof(*runner));
    if (runner == NULL) {
        return NULL;
    }
    runner->argc = argc;
    runner->server_fd = -1;
    if (byedpi_runner_copy_argv(runner, argc, argv) != 0) {
        byedpi_runner_free_argv(runner);
        free(runner);
        return NULL;
    }
    pthread_mutex_init(&runner->mutex, NULL);
    if (pthread_create(&runner->thread, NULL, byedpi_runner_thread, runner) != 0) {
        byedpi_runner_free_argv(runner);
        pthread_mutex_destroy(&runner->mutex);
        free(runner);
        return NULL;
    }
    return runner;
}

int byedpi_runner_wait_port(struct byedpi_runner *runner, int timeout_ms) {
    int waited = 0;
    while (waited <= timeout_ms) {
        pthread_mutex_lock(&runner->mutex);
        int port = runner->port;
        int finished = runner->finished;
        pthread_mutex_unlock(&runner->mutex);
        if (port > 0) {
            __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "listener ready on port=%d after %dms", port, waited);
            return port;
        }
        if (finished) {
            __android_log_print(ANDROID_LOG_WARN, BYEDPI_BRIDGE_TAG, "runner finished before listener was ready");
            return 0;
        }
        usleep(10 * 1000);
        waited += 10;
    }
    __android_log_print(ANDROID_LOG_WARN, BYEDPI_BRIDGE_TAG, "listener wait timed out after %dms", timeout_ms);
    return 0;
}

int byedpi_runner_stop(struct byedpi_runner *runner) {
    if (runner == NULL) {
        return -1;
    }
    pthread_mutex_lock(&runner->mutex);
    int server_fd = runner->server_fd;
    pthread_mutex_unlock(&runner->mutex);
    int result = -1;
    if (server_fd > 0) {
        result = shutdown(server_fd, SHUT_RDWR);
    }
    __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "runner stop result=%d", result);
    return result;
}

int byedpi_runner_join(struct byedpi_runner *runner) {
    int exit_code = -1;
    if (runner == NULL) {
        return exit_code;
    }
    pthread_join(runner->thread, NULL);
    pthread_mutex_lock(&runner->mutex);
    exit_code = runner->exit_code;
    pthread_mutex_unlock(&runner->mutex);
    return exit_code;
}

const char *byedpi_runner_last_error(struct byedpi_runner *runner) {
    if (runner == NULL || runner->last_error == NULL) {
        return "";
    }
    return runner->last_error;
}

void byedpi_runner_free(struct byedpi_runner *runner) {
    if (runner == NULL) {
        return;
    }
    free(runner->last_error);
    byedpi_runner_free_argv(runner);
    pthread_mutex_destroy(&runner->mutex);
    free(runner);
}
