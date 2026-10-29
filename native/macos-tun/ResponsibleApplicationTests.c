#include "ResponsibleApplicationInternal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned Checks;
#define CHECK(value) do { Checks++; if (!(value)) { \
    fprintf(stderr, "Responsible application line %d: %s\n", __LINE__, #value); exit(1); \
} } while (0)

typedef struct {
    PRProcessIdentity socket;
    PRProcessIdentity app;
    unsigned socketReads;
    unsigned appReads;
    unsigned ownerReads;
    bool socketMissing;
    bool appMissing;
    bool socketReused;
    bool appReused;
    bool socketExecChanged;
    bool appExecChanged;
    bool ownerChanged;
    pid_t responsible;
    unsigned connectionReads;
    unsigned signatureReads;
    unsigned connectionFailureAt;
    unsigned signatureFailureAt;
    bool socketReplaced;
    bool zeroGeneration;
} Fixture;

static Fixture NewFixture(void) {
    return (Fixture){
        .socket = {.pid = 101, .user = 501, .realUser = 501, .startSeconds = 100,
            .executable = "/System/Library/Frameworks/WebKit.framework/Networking"},
        .app = {.pid = 102, .user = 501, .realUser = 501, .startSeconds = 90,
            .executable = "/Applications/Safari.app/Contents/MacOS/Safari"},
        .responsible = 102
    };
}

static bool ReadIdentity(void *context, pid_t pid, PRProcessIdentity *result) {
    Fixture *fixture = context;
    if (pid == fixture->socket.pid) {
        if (fixture->socketMissing) return false;
        *result = fixture->socket;
        if (++fixture->socketReads > 1) {
            if (fixture->socketReused) result->startMicroseconds++;
            if (fixture->socketExecChanged) strcpy(result->executable, "/other/socket");
        }
        return true;
    }
    if (pid == fixture->app.pid) {
        if (fixture->appMissing) return false;
        *result = fixture->app;
        if (++fixture->appReads > 1) {
            if (fixture->appReused) result->startSeconds++;
            if (fixture->appExecChanged) strcpy(result->executable, "/other/application");
        }
        return true;
    }
    return false;
}

static pid_t ReadResponsible(void *context, pid_t pid) {
    Fixture *fixture = context;
    if (pid != fixture->socket.pid) return -1;
    if (++fixture->ownerReads > 1 && fixture->ownerChanged) return 103;
    return fixture->responsible;
}

static bool Observe(Fixture *fixture, PRResponsibleApplication *result) {
    return PRObserveResponsibleApplicationWithReaders(101, 501,
        "/Applications/Safari.app/Contents/MacOS/Safari", result,
        fixture, ReadIdentity, ReadResponsible);
}

static void Refuses(Fixture *fixture) {
    PRResponsibleApplication result;
    memset(&result, 0x7f, sizeof(result));
    CHECK(!Observe(fixture, &result));
    PRResponsibleApplication empty = {0};
    CHECK(memcmp(&result, &empty, sizeof(result)) == 0);
}

static bool ReadConnection(void *context, pid_t pid, const PRIPv4TCPConnection *connection, uint64_t *generation) {
    Fixture *fixture = context;
    fixture->connectionReads++;
    if (fixture->connectionReads == fixture->connectionFailureAt || pid != 101 ||
        connection->sourceAddress != 1 || connection->destinationAddress != 2 ||
        connection->sourcePort != 50000 || connection->destinationPort != 443) return false;
    *generation = fixture->zeroGeneration ? 0 : 1000;
    if (fixture->connectionReads > 1 && fixture->socketReplaced) (*generation)++;
    return true;
}

static bool VerifyApplication(void *context, pid_t pid) {
    Fixture *fixture = context;
    return ++fixture->signatureReads != fixture->signatureFailureAt && pid == 102;
}

static void ConnectionChecks(void) {
    PRIPv4TCPConnection tuple = {.sourceAddress = 1, .destinationAddress = 2,
        .sourcePort = 50000, .destinationPort = 443};
    Fixture fixture = NewFixture();
    PRResponsibleApplication result;
    CHECK(PRObserveResponsibleConnectionWithReaders(101, 501, fixture.app.executable, &tuple,
        &result, &fixture, ReadIdentity, ReadResponsible, ReadConnection, VerifyApplication));
    CHECK(fixture.connectionReads == 2 && fixture.signatureReads == 2);
    CHECK(result.socketProcess.pid == 101 && result.application.pid == 102);
    for (unsigned failure = 0; failure < 12; failure++) {
        fixture = NewFixture();
        PRIPv4TCPConnection changed = tuple;
        switch (failure) {
            case 0: fixture.connectionFailureAt = 1; break;
            case 1: fixture.connectionFailureAt = 2; break;
            case 2: fixture.signatureFailureAt = 1; break;
            case 3: fixture.signatureFailureAt = 2; break;
            case 4: fixture.socketReplaced = true; break;
            case 5: fixture.zeroGeneration = true; break;
            case 6: changed.sourceAddress = 3; break;
            case 7: changed.destinationAddress = 3; break;
            case 8: changed.sourcePort++; break;
            case 9: changed.destinationPort++; break;
            case 10: changed.sourceAddress = 0; break;
            case 11: strcpy(fixture.app.executable, "/Applications/Other.app/Contents/MacOS/Other"); break;
        }
        memset(&result, 0x7f, sizeof(result));
        CHECK(!PRObserveResponsibleConnectionWithReaders(101, 501,
            "/Applications/Safari.app/Contents/MacOS/Safari", &changed,
            &result, &fixture, ReadIdentity, ReadResponsible, ReadConnection, VerifyApplication));
        PRResponsibleApplication empty = {0};
        CHECK(memcmp(&result, &empty, sizeof(result)) == 0);
    }
}

int main(void) {
    Fixture fixture = NewFixture();
    PRResponsibleApplication result;
    CHECK(Observe(&fixture, &result));
    CHECK(result.socketProcess.pid == 101 && result.application.pid == 102);
    CHECK(fixture.socketReads == 2 && fixture.appReads == 2 && fixture.ownerReads == 2);

    // A second WebKit client has the same networking executable but a different
    // responsible application. Its flows must not inherit Safari's selection.
    fixture = NewFixture(); strcpy(fixture.app.executable, "/Applications/Other.app/Contents/MacOS/Other"); Refuses(&fixture);
    fixture = NewFixture(); fixture.socketMissing = true; Refuses(&fixture);
    fixture = NewFixture(); fixture.appMissing = true; Refuses(&fixture);
    fixture = NewFixture(); fixture.socketReused = true; Refuses(&fixture);
    fixture = NewFixture(); fixture.appReused = true; Refuses(&fixture);
    fixture = NewFixture(); fixture.socketExecChanged = true; Refuses(&fixture);
    fixture = NewFixture(); fixture.appExecChanged = true; Refuses(&fixture);
    fixture = NewFixture(); fixture.ownerChanged = true; Refuses(&fixture);
    fixture = NewFixture(); fixture.socket.user = 502; Refuses(&fixture);
    fixture = NewFixture(); fixture.app.user = 502; Refuses(&fixture);
    fixture = NewFixture(); fixture.socket.realUser = 502; Refuses(&fixture);
    fixture = NewFixture(); fixture.app.realUser = 502; Refuses(&fixture);
    fixture = NewFixture(); fixture.responsible = -1; Refuses(&fixture);
    fixture = NewFixture(); fixture.responsible = 0; Refuses(&fixture);
    fixture = NewFixture(); fixture.responsible = 1; Refuses(&fixture);
    fixture = NewFixture(); fixture.responsible = 101; Refuses(&fixture);
    fixture = NewFixture(); fixture.socket.startSeconds = 0; Refuses(&fixture);
    fixture = NewFixture(); fixture.app.startMicroseconds = 1000000; Refuses(&fixture);
    fixture = NewFixture(); memset(fixture.app.executable, 'x', sizeof(fixture.app.executable)); Refuses(&fixture);
    fixture = NewFixture(); strcpy(fixture.app.executable, "/applications/Safari.app/Contents/MacOS/Safari"); Refuses(&fixture);
    fixture = NewFixture();
    CHECK(!PRObserveResponsibleApplicationWithReaders(101, 501, fixture.app.executable,
        &result, &fixture, ReadIdentity, NULL));
    CHECK(result.application.pid == 0);
    CHECK(!PRObserveResponsibleApplicationWithReaders(101, 0, fixture.app.executable,
        &result, &fixture, ReadIdentity, ReadResponsible));
    CHECK(!PRObserveResponsibleApplicationWithReaders(1, 501, fixture.app.executable,
        &result, &fixture, ReadIdentity, ReadResponsible));
    CHECK(!PRObserveResponsibleApplicationWithReaders(101, 501, NULL,
        &result, &fixture, ReadIdentity, ReadResponsible));
    CHECK(!PRObserveResponsibleApplicationWithReaders(101, 501, "Safari",
        &result, &fixture, ReadIdentity, ReadResponsible));
    CHECK(!PRObserveResponsibleApplicationWithReaders(101, 501, fixture.app.executable,
        NULL, &fixture, ReadIdentity, ReadResponsible));
    ConnectionChecks();
    printf("Responsible application: %u isolated checks passed; no host routing changed\n", Checks);
    return 0;
}
