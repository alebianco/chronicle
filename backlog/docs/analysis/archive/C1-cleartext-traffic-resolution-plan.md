# Task C1: Security - Cleartext Traffic Resolution Plan

> **Archived.** Its task [[cu-42]] is Done; kept as historical context, not part of the
> active reference set.

**Task ID**: C1  
**Priority**: 🔴 Critical  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

The app currently has `android:usesCleartextTraffic="true"` in the AndroidManifest.xml, which allows unencrypted HTTP connections. This is a **critical security vulnerability** that:
- Exposes user authentication tokens to MITM attacks
- Exposes Plex server URLs and credentials
- Violates Android security best practices
- May fail Google Play Store security review

---

## Current State Analysis

### What I Found:

1. **Manifest Configuration** (`app/src/main/AndroidManifest.xml:22`):
   ```xml
   android:usesCleartextTraffic="true"
   ```

2. **Plex Server Connections**:
   - Plex servers return connection URIs via API (both local and remote)
   - Connection URIs stored in `PlexServer.connections: List<Connection>`
   - Each `Connection` has a `uri: String` field
   - The app tests multiple connections and picks the first successful one
   - **Critical**: The URIs come from Plex API - could be HTTP or HTTPS

3. **Network Stack**:
   - Uses OkHttp + Retrofit
   - Has custom `PlexInterceptor` that adds auth headers
   - Connects to:
     - `https://plex.tv` (login service) - ✅ HTTPS
     - User's Plex server (variable URI) - ⚠️ Could be HTTP or HTTPS
   - No HTTPS enforcement in code

4. **Connection Testing Logic** (`PlexConfig.kt:chooseViableConnections`):
   - Tests all connection URIs from server
   - Picks first successful connection
   - No protocol validation or preference

### Why Cleartext Traffic Was Enabled:

**Likely reason**: Many users run local Plex servers that may only have HTTP enabled (not HTTPS), especially for:
- Local network access (192.168.x.x addresses)
- Users without SSL certificates configured
- Self-signed certificates that would fail validation

---

## Risk Assessment

### Security Risks:
1. **High Risk**: Authentication tokens exposed over HTTP
2. **High Risk**: User credentials visible to network attackers
3. **Medium Risk**: Media streaming URLs exposed
4. **Medium Risk**: Library metadata exposed

### User Impact:
1. **High Impact**: Users with HTTP-only local servers would lose access
2. **Medium Impact**: Users with self-signed certs need special handling
3. **Low Impact**: Most modern Plex servers support HTTPS

---

## Solution Strategy

### Approach: **Hybrid Security with User Choice**

Instead of blocking all HTTP, implement a secure-by-default approach with informed user consent.

### Key Principles:
1. ✅ **HTTPS by default** - prefer and test HTTPS connections first
2. ✅ **User consent for HTTP** - warn users about security risks
3. ✅ **Network Security Config** - allow HTTP only when explicitly enabled
4. ✅ **Certificate validation** - properly handle HTTPS including self-signed certs

---

## Implementation Plan

### Phase 1: Analysis & Preparation (Day 1 - Morning)
**Duration**: 2-3 hours  
**Risk**: Low

#### Tasks:
- [ ] 1.1. Audit all network calls to identify HTTP vs HTTPS usage
- [ ] 1.2. Create comprehensive list of all connection endpoints
- [ ] 1.3. Test with actual Plex servers to understand connection URIs
- [ ] 1.4. Document current user configuration patterns

**Deliverables**:
- Network call audit document
- Test results with various Plex server configs

---

### Phase 2: Implement Network Security Config (Day 1 - Afternoon)
**Duration**: 3-4 hours  
**Risk**: Low

#### Tasks:
- [ ] 2.1. Create `res/xml/network_security_config.xml`
- [ ] 2.2. Configure to allow cleartext only when user setting enabled
- [ ] 2.3. Reference in AndroidManifest
- [ ] 2.4. Remove `usesCleartextTraffic="true"` from manifest

**Implementation**:

```xml
<!-- res/xml/network_security_config.xml -->
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Default: HTTPS only -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" /> <!-- For user-installed certs -->
        </trust-anchors>
    </base-config>
    
    <!-- Domain-specific config - will be dynamically managed via code -->
    <!-- Empty by default, can be extended if needed -->
</network-security-config>
```

```xml
<!-- AndroidManifest.xml -->
<application
    ...
    android:networkSecurityConfig="@xml/network_security_config"
    android:usesCleartextTraffic="false">
```

**Deliverables**:
- Network security config file
- Updated manifest

---

### Phase 3: Connection Logic Enhancement (Day 2 - Morning)
**Duration**: 4-5 hours  
**Risk**: Medium

#### Tasks:
- [ ] 3.1. Update `PlexConfig.chooseViableConnections` to prefer HTTPS
- [ ] 3.2. Add protocol detection and ordering
- [ ] 3.3. Add HTTPS validation before HTTP fallback
- [ ] 3.4. Implement connection upgrade (HTTP → HTTPS test)

**Implementation**:

```kotlin
// PlexConfig.kt
private suspend fun chooseViableConnections(
    plexMediaService: PlexMediaService
): ConnectionResult {
    val connections = connectionSet.toList()
    
    // Strategy: Test in order of security preference
    val sortedConnections = connections.sortedWith(
        compareByDescending<Connection> { it.local } // Local first
            .thenByDescending { it.uri.startsWith("https://") } // HTTPS preferred
    )
    
    // Test HTTPS connections first
    val httpsConnections = sortedConnections.filter { 
        it.uri.startsWith("https://") 
    }
    
    for (conn in httpsConnections) {
        val result = testConnection(conn, plexMediaService)
        if (result is Success) {
            Timber.i("✅ HTTPS connection successful: ${conn.uri}")
            return result
        }
    }
    
    // If HTTPS failed, check if HTTP is allowed
    val httpConnections = sortedConnections.filter { 
        it.uri.startsWith("http://") 
    }
    
    if (httpConnections.isNotEmpty()) {
        // Try to upgrade HTTP to HTTPS first
        for (conn in httpConnections) {
            val httpsUri = conn.uri.replace("http://", "https://")
            val httpsConn = conn.copy(uri = httpsUri)
            val result = testConnection(httpsConn, plexMediaService)
            if (result is Success) {
                Timber.i("✅ HTTP upgraded to HTTPS: $httpsUri")
                return result
            }
        }
        
        // If user has allowed HTTP, try HTTP connections
        if (plexPrefsRepo.allowInsecureConnections) {
            for (conn in httpConnections) {
                Timber.w("⚠️ Attempting insecure HTTP connection: ${conn.uri}")
                val result = testConnection(conn, plexMediaService)
                if (result is Success) {
                    // Log warning about insecure connection
                    Timber.w("⚠️ SECURITY WARNING: Connected via HTTP: ${conn.uri}")
                    return result
                }
            }
        } else {
            Timber.e("❌ HTTP connections available but not allowed by user")
            return Failure("HTTPS connections failed. Enable insecure connections in settings to use HTTP.")
        }
    }
    
    return Failure("All connection attempts failed")
}
```

**Deliverables**:
- Enhanced connection logic with HTTPS preference
- HTTP upgrade attempt mechanism
- Security logging

---

### Phase 4: User Settings & UI (Day 2 - Afternoon)
**Duration**: 3-4 hours  
**Risk**: Low

#### Tasks:
- [ ] 4.1. Add "Allow Insecure Connections" setting to PrefsRepo
- [ ] 4.2. Add setting to Settings UI
- [ ] 4.3. Create security warning dialog
- [ ] 4.4. Add connection security indicator in UI
- [ ] 4.5. Add strings to strings.xml

**Implementation**:

```kotlin
// PrefsRepo.kt
interface PrefsRepo {
    var allowInsecureConnections: Boolean
    // Default: false (secure by default)
}

// SharedPreferencesPrefsRepo.kt
override var allowInsecureConnections: Boolean
    get() = prefs.getBoolean(PREF_ALLOW_INSECURE, false)
    set(value) = prefs.edit().putBoolean(PREF_ALLOW_INSECURE, value).apply()
```

```xml
<!-- strings.xml -->
<string name="pref_allow_insecure_title">Allow Insecure Connections</string>
<string name="pref_allow_insecure_summary">Allow HTTP connections to Plex server (not recommended)</string>
<string name="pref_allow_insecure_warning_title">Security Warning</string>
<string name="pref_allow_insecure_warning_message">Enabling HTTP connections may expose your Plex credentials and media data to network attackers. Only enable this for local servers you trust.\n\nRecommended: Configure HTTPS on your Plex server instead.</string>
<string name="connection_secure">Secure (HTTPS)</string>
<string name="connection_insecure">Insecure (HTTP)</string>
```

```kotlin
// SettingsFragment.kt - Add preference with warning dialog
findPreference<SwitchPreference>("allow_insecure_connections")?.apply {
    setOnPreferenceChangeListener { _, newValue ->
        if (newValue == true) {
            // Show warning dialog
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_allow_insecure_warning_title)
                .setMessage(R.string.pref_allow_insecure_warning_message)
                .setPositiveButton(R.string.enable) { _, _ ->
                    prefsRepo.allowInsecureConnections = true
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            false // Don't update yet, wait for dialog
        } else {
            true // Allow disabling without dialog
        }
    }
}
```

**Deliverables**:
- New user setting with security warning
- UI updates to show connection security status
- User-facing strings

---

### Phase 5: Testing (Day 3)
**Duration**: Full day  
**Risk**: Medium

#### Test Cases:
- [ ] 5.1. **HTTPS-only server** (most secure):
  - ✅ Should connect successfully
  - ✅ No warnings shown
  - ✅ Connection indicator shows "Secure"

- [ ] 5.2. **HTTP-only local server** (insecure):
  - ✅ Connection should fail initially
  - ✅ User sees error message about HTTPS
  - ✅ User enables "Allow Insecure" setting
  - ✅ Connection succeeds with warning
  - ✅ Connection indicator shows "Insecure"

- [ ] 5.3. **Server with both HTTP and HTTPS**:
  - ✅ HTTPS connection chosen automatically
  - ✅ HTTP ignored even if faster
  - ✅ Connection indicator shows "Secure"

- [ ] 5.4. **Self-signed certificate**:
  - ✅ User can install certificate
  - ✅ Connection works after install
  - ✅ Clear instructions provided

- [ ] 5.5. **Migration test** (existing users):
  - ✅ Existing users with HTTP servers get clear message
  - ✅ Easy path to enable insecure connections
  - ✅ No data loss

- [ ] 5.6. **New user experience**:
  - ✅ Setup flow works with HTTPS servers
  - ✅ Clear guidance if HTTPS unavailable

**Testing Environments**:
- Local Plex server (HTTP only)
- Local Plex server (HTTPS with valid cert)
- Local Plex server (HTTPS with self-signed cert)
- Remote Plex server (HTTPS)
- Mix of local + remote connections

**Deliverables**:
- Test report with all cases
- Screenshots of user flows
- Performance metrics

---

### Phase 6: Documentation & Review (Day 3 - End of Day)
**Duration**: 2 hours  
**Risk**: Low

#### Tasks:
- [ ] 6.1. Update architecture documentation
- [ ] 6.2. Add security section to README
- [ ] 6.3. Create user guide for HTTPS setup
- [ ] 6.4. Update CONTRIBUTING.md with security guidelines
- [ ] 6.5. Add inline code comments for security-critical sections
- [ ] 6.6. Create PR with comprehensive description

**Documentation to Add**:

```markdown
## Security

Chronicle connects to your Plex server securely via HTTPS by default. 

### HTTPS Requirement
- All connections use HTTPS to protect your credentials
- HTTP connections are disabled by default
- You can enable HTTP for local servers in Settings (not recommended)

### Self-Signed Certificates
If your Plex server uses a self-signed certificate:
1. Install the certificate on your Android device
2. Settings → Security → Install from storage
3. Chronicle will trust your certificate

### For Server Administrators
We strongly recommend configuring HTTPS on your Plex server:
- Use Let's Encrypt for free certificates
- Or use your own domain with valid SSL
- See: [Plex HTTPS Guide](https://support.plex.tv/articles/206225077-how-to-use-secure-server-connections/)
```

**Deliverables**:
- Updated documentation
- PR ready for review

---

## Rollback Plan

If issues arise:

1. **Immediate Rollback** (< 1 hour):
   - Revert manifest change
   - Re-enable `usesCleartextTraffic="true"`
   - Keep new code changes for future attempt

2. **Partial Rollback** (if HTTP needed temporarily):
   - Keep network security config
   - Set `cleartextTrafficPermitted="true"` globally
   - Keep user setting for future proper fix

3. **Data Safety**:
   - No database changes = no migration needed
   - User preferences preserved
   - No data loss risk

---

## Success Criteria

✅ **Must Have**:
1. `usesCleartextTraffic="true"` removed from manifest
2. HTTPS connections work for all users with HTTPS servers
3. Clear error messages for users with HTTP-only servers
4. User setting to enable HTTP with security warning
5. All tests pass
6. No regressions in connection logic

✅ **Should Have**:
1. HTTPS upgrade attempt (HTTP → HTTPS)
2. Connection security indicator in UI
3. Comprehensive documentation
4. User guide for HTTPS setup

✅ **Nice to Have**:
1. Certificate management guidance
2. Server configuration recommendations
3. Analytics on HTTP vs HTTPS usage (privacy-respecting)

---

## Open Questions & Clarifications Needed

### 🤔 Question 1: User Base Impact
**Q**: What percentage of Chronicle users have HTTP-only Plex servers?  
**Why it matters**: Determines if we need a gentler migration or can be strict  
**Options**:
- A) Strict: No HTTP at all (most secure, may break users)
- B) Opt-in: HTTP available with warning (balanced)
- C) Opt-out: HTTP default with option to disable (less secure)

**My Recommendation**: Option B (Opt-in with warning) - secure by default, flexible for edge cases

---

### 🤔 Question 2: Certificate Handling
**Q**: Should we handle self-signed certificates specially?  
**Why it matters**: Many self-hosted users have self-signed certs  
**Options**:
- A) Require Android system certificate install (more secure, more friction)
- B) Allow in-app certificate trust (less secure, easier UX)
- C) Ignore certificate validation (insecure, not recommended)

**My Recommendation**: Option A - guide users to install certificates properly

---

### 🤔 Question 3: Migration Communication
**Q**: How should we communicate this breaking change to existing users?  
**Options**:
- A) In-app migration dialog on first launch after update
- B) Release notes only
- C) Email to users (if possible)
- D) Gradual rollout to measure impact

**My Recommendation**: Option A + D - dialog + staged rollout

---

### 🤔 Question 4: Testing Resources
**Q**: Do we have access to various Plex server configurations for testing?  
**Need**:
- HTTP-only local server
- HTTPS local server (valid cert)
- HTTPS local server (self-signed)
- Remote HTTPS server
- Mixed connection scenarios

**Can I proceed with**: Emulated scenarios using localhost or need real Plex servers?

---

### 🤔 Question 5: Timeline Flexibility
**Q**: Is the 2-3 day estimate acceptable or do we need faster resolution?  
**Impact**:
- Faster = higher risk, less testing
- Slower = more thorough, safer

**Current estimate**: 3 days (thorough testing included)  
**Fast track possible**: 1.5 days (minimal testing) - **NOT recommended for security issue**

---

## Risk Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|------------|---------|------------|
| Breaking HTTP users | High | High | Opt-in setting + clear messaging |
| Self-signed cert issues | Medium | Medium | Documentation + Android cert guide |
| Connection logic bugs | Low | High | Comprehensive testing + staged rollout |
| User confusion | Medium | Medium | Clear UI messages + docs |
| Performance impact | Low | Low | Connection caching + monitoring |

---

## Dependencies

**None** - This task is self-contained

**Blocks**:
- App store submission (security review requirement)
- Any compliance audits

**Blocked by**: None

---

## Estimated Effort Breakdown

| Phase | Optimistic | Realistic | Pessimistic |
|-------|-----------|-----------|-------------|
| 1. Analysis | 2h | 3h | 4h |
| 2. Network Config | 2h | 3h | 4h |
| 3. Connection Logic | 3h | 5h | 7h |
| 4. UI & Settings | 2h | 4h | 5h |
| 5. Testing | 4h | 8h | 12h |
| 6. Documentation | 1h | 2h | 3h |
| **Total** | **14h (1.75d)** | **25h (3.1d)** | **35h (4.4d)** |

**Recommended**: 3 days (24 hours effort) with buffer for unexpected issues

---

## Approval Checklist

Before proceeding, please confirm:

- [ ] **Strategy approved**: Hybrid approach (HTTPS default + opt-in HTTP)
- [ ] **User impact acceptable**: Existing HTTP users will need to enable setting
- [ ] **Timeline acceptable**: 3 days for thorough implementation
- [ ] **Testing plan approved**: Access to various Plex configurations or acceptable to use emulated scenarios
- [ ] **Migration approach**: In-app dialog for existing users + staged rollout
- [ ] **Certificate handling**: Guide users to install system certificates (no in-app trust)
- [ ] **Open questions resolved**: Answers to questions 1-5 above

---

## Next Steps After Approval

1. ✅ Start Phase 1: Analysis & Preparation
2. ✅ Create feature branch: `feature/C1-remove-cleartext-traffic`
3. ✅ Daily progress updates
4. ✅ Request code review after Phase 5 (testing)
5. ✅ Staged rollout plan for release

---

**Ready to proceed?** Please review this plan and provide:
1. ✅ Approval or changes needed
2. 📝 Answers to open questions (1-5)
3. 🎯 Any additional requirements or constraints

---

*Created: 2025-11-28*  
*Owner: Engineering Team*  
*Reviewer: Security Team / Tech Lead*

