─────┬──────────────────────────────────────────────────────────────────────────

> **Archived.** Its task [[cu-53]] is Won't Do; kept as historical context, not part of the
> active reference set.
     │ STDIN
     │ Size: -
─────┼──────────────────────────────────────────────────────────────────────────
   1 │ # Task M3: Billing Library Update Required Resolution Plan
   2 │ **Task ID**: M3  
   3 │ **Priority**: 🟡 Medium (Compliance)  
   4 │ **Created**: 2025-11-28  
   5 │ **Status**: Planning - Awaiting Approval
   6 │ ---
   7 │ ## Problem Statement
   8 │ A TODO mentions the billing version needs update by November. Google Play Billing Library has mandatory upgrade requirements to stay compliant.
   9 │ **Current State**:
  10 │ - TODO item mentions billing library update deadline
  11 │ - Need to verify current version
  12 │ - Google requires Play Billing Library 5.x minimum (as of 2023)
  13 │ - Play Billing Library 6.x is current (2024)
  14 │ **Impact**:
  15 │ - App may be removed from Play Store if non-compliant
  16 │ - Can't publish updates without current billing library
  17 │ - Missing new features and improvements
  18 │ ---
  19 │ ## Implementation Plan
  20 │ ### Phase 1: Check Current State (1 hour)
  21 │ ```bash
  22 │ # Check current version
  23 │ grep "billing" gradle/libs.versions.toml
  24 │ grep "billing" app/build.gradle.kts
  25 │ # Find billing usage
  26 │ grep -r "BillingClient" app/src/main --include="*.kt"
  27 │ grep -r "Purchase" app/src/main --include="*.kt"
  28 │ ```
  29 │ **Verify Requirements**:
  30 │ - Check Google Play Console for warnings
  31 │ - Review Play Billing Library releases: https://developer.android.com/google/play/billing/release-notes
  32 │ ---
  33 │ ### Phase 2: Update Library (2 hours)
  34 │ **Update to Latest**:
  35 │ ```toml
  36 │ # gradle/libs.versions.toml
  37 │ billing = "6.1.0"  # or latest
  38 │ [libraries]
  39 │ billing = { group = "com.android.billingclient", name = "billing-ktx", version.ref = "billing" }
  40 │ ```
  41 │ **Review Migration Guide**:
  42 │ - 5.x → 6.x changes
  43 │ - API deprecations
  44 │ - New required implementations
  45 │ ---
  46 │ ### Phase 3: Update Code (4-6 hours)
  47 │ **Common Migration Patterns**:
  48 │ ```kotlin
  49 │ // Update BillingClient initialization
  50 │ val billingClient = BillingClient.newBuilder(context)
  51 │     .setListener(purchasesUpdatedListener)
  52 │     .enablePendingPurchases()  // Required
  53 │     .build()
  54 │ // Update purchase flow
  55 │ billingClient.launchBillingFlow(activity, flowParams)
  56 │ // Update query methods
  57 │ billingClient.queryProductDetailsAsync(queryProductDetailsParams) { billingResult, productDetailsList ->
  58 │     // Handle response
  59 │ }
  60 │ ```
  61 │ **Test All Flows**:
  62 │ - [ ] App launches
  63 │ - [ ] In-app purchase UI works
  64 │ - [ ] Purchase flow completes
  65 │ - [ ] Purchase verification works
  66 │ - [ ] Subscription handling (if used)
  67 │ - [ ] Restore purchases works
  68 │ ---
  69 │ ### Phase 4: Test Purchase Flows (4 hours)
  70 │ **Test Scenarios**:
  71 │ 1. **First Purchase**:
  72 │    - User never purchased before
  73 │    - Complete purchase flow
  74 │    - Verify entitlement granted
  75 │ 2. **Existing Purchase**:
  76 │    - User already owns premium
  77 │    - Verify recognized on app launch
  78 │ 3. **Restore Purchases**:
  79 │    - Reinstall app
  80 │    - Restore purchases
  81 │    - Verify premium restored
  82 │ 4. **Errors**:
  83 │    - Cancel purchase
  84 │    - Payment failure
  85 │    - Network error during purchase
  86 │ 5. **Edge Cases**:
  87 │    - Background during purchase
  88 │    - Multiple purchase attempts
  89 │    - Offline mode
  90 │ ---
  91 │ ## Success Criteria
  92 │ - [ ] Latest billing library version
  93 │ - [ ] All purchase flows tested
  94 │ - [ ] No Play Console warnings
  95 │ - [ ] Can publish app updates
  96 │ - [ ] Documentation updated
  97 │ ---
  98 │ ## Estimated Effort
  99 │ **Total**: 2-3 days (16-24 hours)
 100 │ ---
 101 │ ## Approval Needed
 102 │ - [ ] Can update billing library now
 103 │ - [ ] Have test accounts for purchase testing
 104 │ - [ ] Timeline OK (2-3 days)
 105 │ ---
 106 │ *Created: 2025-11-28*  
 107 │ *Owner: Monetization Team*  
 108 │ *Estimated Completion: 2-3 days*
─────┴──────────────────────────────────────────────────────────────────────────
