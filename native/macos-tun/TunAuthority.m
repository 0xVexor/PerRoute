#import "TunAuthority.h"
#import "TunNetwork.h"
#import <time.h>
static double Now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return t.tv_sec+t.tv_nsec/1e9; }
static BOOL Fail(NSError **error, NSString *message) { if (error) *error = PRTunError(message); return NO; }
@implementation PRTunAuthority {
    PRTunStore *_store;
    PRTunSession *_session;
    PRTunHealth _health;
    id _owner;
    uid_t _user;
    double _deadline;
    BOOL _ready;
    id _failedOwner;
    uid_t _failedUser;
    NSError *_lastFailure;
    NSString *_networkChangedSession;
}
- (instancetype)initWithStore:(PRTunStore *)store {
    self = [super init]; if (self) _store = store; return self;
}
- (BOOL)active { return _session != nil; }
- (BOOL)recoveryForUser:(uid_t)uid { return _session && !_owner && uid == _user; }
- (BOOL)ownedBy:(id)owner user:(uid_t)uid { return _session && owner && owner == _owner && uid == _user; }
- (BOOL)stopInternal:(NSError **)error {
    if (_session && ![_session stop:error]) return NO;
    _session = nil; _health = nil; _owner = nil; _user = 0; _ready = NO;
    return YES;
}
- (NSDictionary *)startOwner:(id)owner user:(uid_t)uid create:(PRTunCreate)create health:(PRTunHealth)health error:(NSError **)error {
    if (!owner || !uid || !create || !health) { Fail(error,@"Invalid native TUN ownership context"); return nil; }
    if (_session) { Fail(error,@"Stop the existing per-app session before starting another"); return nil; }
    if (![self recover:error]) return nil;
    _failedOwner = nil; _failedUser = 0; _lastFailure = nil; _networkChangedSession = nil;
    PRTunSession *session = create(error);
    if (!session) return nil;
    _session = session; _owner = owner; _user = uid; _health = [health copy]; _deadline = Now()+10; _ready = NO;
    return [self statusOwner:owner user:uid error:error];
}
- (BOOL)observe:(NSError **)error {
    if (!_session) return YES;
    NSError *failure = nil;
    BOOL ready = NO;
    BOOL healthy = [_session poll:&failure] && _health(&ready,&failure);
    if (!healthy || (!ready && Now() >= _deadline) || (_ready && !ready)) {
        if (!failure) failure = PRTunError(_ready ? @"The active tunnel interface or routes were lost" : @"The tunnel did not become ready before its deadline");
        id failedOwner = _owner;
        uid_t failedUser = _user;
        NSString *networkSession = _ready && PRTunIsNetworkChange(failure) ? _session.identifier : nil;
        NSError *cleanup = nil;
        BOOL stopped = [self stopInternal:&cleanup];
        if (!stopped) failure = cleanup ?: failure;
        _networkChangedSession = stopped ? networkSession : nil;
        // The timer can finish cleanup before the next Java poll. Preserve its failure for
        // the same authenticated owner instead of returning a misleading stopped success.
        _failedOwner = failedOwner; _failedUser = failedUser; _lastFailure = failure;
        if (error) *error = failure;
        return NO;
    }
    _ready = ready;
    return YES;
}
- (NSDictionary *)statusOwner:(id)owner user:(uid_t)uid error:(NSError **)error {
    if (_lastFailure && owner == _failedOwner && uid == _failedUser) {
        if (_networkChangedSession && !_session) {
            NSArray *sessions = [_store sessions:error];
            if (!sessions) return nil;
            if (sessions.count) { Fail(error,@"TUN recovery remains pending"); return nil; }
            return @{@"pending":@NO,@"owned":@NO,@"phase":@"network-changed",@"session":_networkChangedSession};
        }
        if (error) *error = _lastFailure;
        return nil;
    }
    if (_session && ![self ownedBy:owner user:uid]) {
        if ([self recoveryForUser:uid]) return @{@"pending":@YES,@"owned":@NO,@"phase":@"recovering"};
        Fail(error,@"Another authenticated connection owns per-app routing"); return nil;
    }
    NSError *observation = nil;
    if (![self observe:&observation]) {
        // Return the terminal result on the first poll as well as after timer cleanup.
        if (_networkChangedSession) return [self statusOwner:owner user:uid error:error];
        if (error) *error = observation;
        return nil;
    }
    NSArray *sessions = [_store sessions:error];
    if (!sessions) return nil;
    if (!_session) return @{@"pending":sessions.count ? @YES : @NO,@"owned":@NO,@"phase":@"stopped"};
    return @{@"pending":@YES,@"owned":@YES,@"phase":_ready ? @"ready" : @"starting",@"session":_session.identifier};
}
- (BOOL)stopOwner:(id)owner user:(uid_t)uid error:(NSError **)error {
    if (_session && ![self ownedBy:owner user:uid] && ![self recoveryForUser:uid]) return Fail(error,@"Another authenticated connection owns per-app routing");
    BOOL stopped = [self stopInternal:error] && [self recover:error];
    if (stopped && owner == _failedOwner && uid == _failedUser) {
        _failedOwner = nil; _failedUser = 0; _lastFailure = nil; _networkChangedSession = nil;
    }
    return stopped;
}
- (void)invalidate:(id)owner {
    if (owner && owner == _failedOwner) { _failedOwner = nil; _failedUser = 0; _lastFailure = nil; _networkChangedSession = nil; }
    if (owner && owner == _owner) {
        NSError *error = nil;
        if (![self stopInternal:&error]) {
            // The dead connection conveys no live ownership. The same user may
            // explicitly retry cleanup; no replacement session may start meanwhile.
            _owner = nil;
            NSLog(@"PerRoute TUN owner-loss recovery remains pending");
        }
    }
}
- (BOOL)recover:(NSError **)error {
    NSError *failure = nil;
    BOOL observed = [self observe:&failure];
    NSArray *sessions = [_store sessions:error];
    if (!sessions) return NO;
    BOOL recovered = YES;
    for (NSString *session in sessions) {
        if (_session && [session isEqual:_session.identifier]) continue;
        NSError *cleanup = nil;
        if (![_store cleanup:session error:&cleanup]) { recovered = NO; if (!failure) failure = cleanup; }
    }
    if ((!observed || !recovered) && error) *error = failure ?: PRTunError(@"TUN recovery remains pending");
    return observed && recovered;
}
@end
