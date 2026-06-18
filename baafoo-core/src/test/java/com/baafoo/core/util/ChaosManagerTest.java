package com.baafoo.core.util;

import com.baafoo.core.model.ChaosProfile;
import com.baafoo.core.model.ChaosProfile.ChaosRule;
import com.baafoo.core.model.FaultInjection;
import com.baafoo.core.model.FaultInjection.Fault;
import com.baafoo.core.model.Rule;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ChaosManager} (PRD §6 R-S13).
 */
public class ChaosManagerTest {

    private ChaosManager manager;

    @Before
    public void setUp() {
        manager = new ChaosManager();
    }

    // ===== Profile registration =====

    @Test
    public void testRegisterProfile() {
        ChaosProfile profile = buildSimpleProfile("test-scenario");
        manager.registerProfile(profile);

        ChaosProfile retrieved = manager.getProfile("test-scenario");
        assertNotNull(retrieved);
        assertEquals("test-scenario", retrieved.getName());
    }

    @Test
    public void testRegisterProfileReplacesExisting() {
        ChaosProfile p1 = buildSimpleProfile("scenario");
        p1.setDescription("version 1");
        manager.registerProfile(p1);

        ChaosProfile p2 = buildSimpleProfile("scenario");
        p2.setDescription("version 2");
        manager.registerProfile(p2);

        assertEquals("version 2", manager.getProfile("scenario").getDescription());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterNullProfileThrows() {
        manager.registerProfile(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterProfileWithNullNameThrows() {
        ChaosProfile profile = new ChaosProfile();
        manager.registerProfile(profile);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterProfileWithEmptyNameThrows() {
        ChaosProfile profile = new ChaosProfile();
        profile.setName("");
        manager.registerProfile(profile);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterProfileWithSpaceInNameThrows() {
        // S8 fix: profile names must match [a-zA-Z0-9_-]
        manager.registerProfile(buildSimpleProfile("has space"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterProfileWithSlashInNameThrows() {
        manager.registerProfile(buildSimpleProfile("has/slash"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterProfileWithChineseInNameThrows() {
        manager.registerProfile(buildSimpleProfile("中文"));
    }

    @Test
    public void testRegisterProfileWithValidSpecialChars() {
        // Underscore and hyphen are allowed
        manager.registerProfile(buildSimpleProfile("my_profile-1"));
        assertEquals(1, manager.listProfiles().size());
    }

    @Test
    public void testRegisterMultipleProfiles() {
        manager.registerProfiles(Arrays.asList(
                buildSimpleProfile("scenario-1"),
                buildSimpleProfile("scenario-2"),
                buildSimpleProfile("scenario-3")));

        assertEquals(3, manager.listProfiles().size());
    }

    @Test
    public void testRegisterNullListDoesNothing() {
        manager.registerProfiles(null);
        assertEquals(0, manager.listProfiles().size());
    }

    @Test
    public void testGetNonExistentProfileReturnsNull() {
        assertNull(manager.getProfile("nonexistent"));
    }

    @Test
    public void testListProfilesEmpty() {
        assertTrue(manager.listProfiles().isEmpty());
    }

    // ===== Activation =====

    @Test
    public void testActivateProfileSuccess() {
        ChaosProfile profile = buildSimpleProfile("test-scenario");
        manager.registerProfile(profile);

        ChaosManager.ActivateResult result = manager.activate("test-scenario");

        assertTrue(result.isSuccess());
        assertEquals(ChaosManager.ActivateResult.Status.SUCCESS, result.getStatus());
        assertEquals(1, result.getGeneratedRules().size());
        assertTrue(manager.isActive("test-scenario"));
    }

    @Test
    public void testActivateNonExistentProfile() {
        ChaosManager.ActivateResult result = manager.activate("nonexistent");

        assertFalse(result.isSuccess());
        assertEquals(ChaosManager.ActivateResult.Status.NOT_FOUND, result.getStatus());
        assertTrue(result.getGeneratedRules().isEmpty());
    }

    @Test
    public void testActivateAlreadyActiveProfile() {
        manager.registerProfile(buildSimpleProfile("test"));
        manager.activate("test");

        ChaosManager.ActivateResult result = manager.activate("test");

        assertFalse(result.isSuccess());
        assertEquals(ChaosManager.ActivateResult.Status.ALREADY_ACTIVE, result.getStatus());
    }

    @Test
    public void testActivatedRuleHasCorrectId() {
        manager.registerProfile(buildSimpleProfile("my-scenario"));
        ChaosManager.ActivateResult result = manager.activate("my-scenario");

        Rule rule = result.getGeneratedRules().get(0);
        assertEquals("chaos-my-scenario-0", rule.getId());
    }

    @Test
    public void testActivatedRuleHasChaosTags() {
        manager.registerProfile(buildSimpleProfile("test"));
        ChaosManager.ActivateResult result = manager.activate("test");

        Rule rule = result.getGeneratedRules().get(0);
        assertTrue(rule.getTags().contains("chaos"));
        assertTrue(rule.getTags().contains("chaos-test"));
    }

    @Test
    public void testActivatedRuleHasHigherPriority() {
        manager.registerProfile(buildSimpleProfile("test"));
        ChaosManager.ActivateResult result = manager.activate("test");

        Rule rule = result.getGeneratedRules().get(0);
        assertEquals(50, rule.getPriority());
        assertTrue(rule.getPriority() < 100); // Higher priority than default
    }

    @Test
    public void testActivatedRuleHasMethodAndPathConditions() {
        manager.registerProfile(buildSimpleProfile("test"));
        ChaosManager.ActivateResult result = manager.activate("test");

        Rule rule = result.getGeneratedRules().get(0);
        assertEquals(2, rule.getConditions().size());
        assertEquals("method", rule.getConditions().get(0).getType());
        assertEquals("GET", rule.getConditions().get(0).getValue());
        assertEquals("path", rule.getConditions().get(1).getType());
        assertEquals("regex", rule.getConditions().get(1).getOperator());
    }

    @Test
    public void testActivatedRuleHasFaultInjection() {
        manager.registerProfile(buildSimpleProfile("test"));
        ChaosManager.ActivateResult result = manager.activate("test");

        Rule rule = result.getGeneratedRules().get(0);
        assertNotNull(rule.getFaultInjection());
        assertEquals(1, rule.getFaultInjection().getFaults().size());
        assertEquals("HTTP_ERROR", rule.getFaultInjection().getFaults().get(0).getType());
    }

    @Test
    public void testActivatedRuleHasEnvironmentsFromProfile() {
        ChaosProfile profile = buildSimpleProfile("test");
        profile.setEnvironments(Arrays.asList("ft-1", "ft-2"));
        manager.registerProfile(profile);

        ChaosManager.ActivateResult result = manager.activate("test");

        Rule rule = result.getGeneratedRules().get(0);
        assertEquals(Arrays.asList("ft-1", "ft-2"), rule.getEnvironments());
    }

    @Test
    public void testActivatedRuleHasDefaultResponse() {
        manager.registerProfile(buildSimpleProfile("test"));
        ChaosManager.ActivateResult result = manager.activate("test");

        Rule rule = result.getGeneratedRules().get(0);
        assertEquals(1, rule.getResponses().size());
        assertEquals(200, rule.getResponses().get(0).getStatusCode());
    }

    @Test
    public void testActivateProfileWithMultipleRules() {
        ChaosProfile profile = new ChaosProfile();
        profile.setName("multi");
        profile.setRules(Arrays.asList(
                buildChaosRule("rule-1", "GET", "/api/users"),
                buildChaosRule("rule-2", "POST", "/api/orders"),
                buildChaosRule("rule-3", "DELETE", "/api/items/{id}")));
        manager.registerProfile(profile);

        ChaosManager.ActivateResult result = manager.activate("multi");

        assertTrue(result.isSuccess());
        assertEquals(3, result.getGeneratedRules().size());
        assertEquals("chaos-multi-0", result.getGeneratedRules().get(0).getId());
        assertEquals("chaos-multi-1", result.getGeneratedRules().get(1).getId());
        assertEquals("chaos-multi-2", result.getGeneratedRules().get(2).getId());
    }

    @Test
    public void testActivateProfileWithNoRules() {
        ChaosProfile profile = new ChaosProfile();
        profile.setName("empty");
        manager.registerProfile(profile);

        ChaosManager.ActivateResult result = manager.activate("empty");

        assertTrue(result.isSuccess());
        assertTrue(result.getGeneratedRules().isEmpty());
        assertTrue(manager.isActive("empty"));
    }

    // ===== Deactivation =====

    @Test
    public void testDeactivateActiveProfile() {
        manager.registerProfile(buildSimpleProfile("test"));
        manager.activate("test");

        ChaosManager.DeactivateResult result = manager.deactivate("test");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getRuleIds().size());
        assertEquals("chaos-test-0", result.getRuleIds().get(0));
        assertFalse(manager.isActive("test"));
    }

    @Test
    public void testDeactivateNonActiveProfile() {
        manager.registerProfile(buildSimpleProfile("test"));

        ChaosManager.DeactivateResult result = manager.deactivate("test");

        assertFalse(result.isSuccess());
        assertEquals(ChaosManager.DeactivateResult.Status.NOT_ACTIVE, result.getStatus());
    }

    @Test
    public void testDeactivateNonExistentProfile() {
        ChaosManager.DeactivateResult result = manager.deactivate("nonexistent");

        assertFalse(result.isSuccess());
        assertEquals(ChaosManager.DeactivateResult.Status.NOT_ACTIVE, result.getStatus());
    }

    @Test
    public void testDeactivateReturnsAllRuleIds() {
        ChaosProfile profile = new ChaosProfile();
        profile.setName("multi");
        profile.setRules(Arrays.asList(
                buildChaosRule("r1", "GET", "/a"),
                buildChaosRule("r2", "GET", "/b"),
                buildChaosRule("r3", "GET", "/c")));
        manager.registerProfile(profile);
        manager.activate("multi");

        ChaosManager.DeactivateResult result = manager.deactivate("multi");

        assertEquals(3, result.getRuleIds().size());
        assertTrue(result.getRuleIds().contains("chaos-multi-0"));
        assertTrue(result.getRuleIds().contains("chaos-multi-1"));
        assertTrue(result.getRuleIds().contains("chaos-multi-2"));
    }

    @Test
    public void testCanReactivateAfterDeactivation() {
        manager.registerProfile(buildSimpleProfile("test"));
        manager.activate("test");
        manager.deactivate("test");

        ChaosManager.ActivateResult result = manager.activate("test");

        assertTrue(result.isSuccess());
    }

    // ===== Emergency stop =====

    @Test
    public void testEmergencyStopWithNoActiveProfiles() {
        ChaosManager.EmergencyStopResult result = manager.emergencyStop();

        assertTrue(result.getDeactivatedProfiles().isEmpty());
        assertTrue(result.getRuleIds().isEmpty());
    }

    @Test
    public void testEmergencyStopWithSingleActiveProfile() {
        manager.registerProfile(buildSimpleProfile("test"));
        manager.activate("test");

        ChaosManager.EmergencyStopResult result = manager.emergencyStop();

        assertEquals(1, result.getDeactivatedProfiles().size());
        assertEquals("test", result.getDeactivatedProfiles().get(0));
        assertEquals(1, result.getRuleIds().size());
        assertEquals("chaos-test-0", result.getRuleIds().get(0));
    }

    @Test
    public void testEmergencyStopWithMultipleActiveProfiles() {
        manager.registerProfile(buildMultiRuleProfile("a", 2));
        manager.registerProfile(buildMultiRuleProfile("b", 3));
        manager.activate("a");
        manager.activate("b");

        ChaosManager.EmergencyStopResult result = manager.emergencyStop();

        assertEquals(2, result.getDeactivatedProfiles().size());
        assertEquals(5, result.getRuleIds().size());
        assertFalse(manager.isActive("a"));
        assertFalse(manager.isActive("b"));
    }

    @Test
    public void testEmergencyStopClearsAllActive() {
        manager.registerProfile(buildSimpleProfile("a"));
        manager.registerProfile(buildSimpleProfile("b"));
        manager.registerProfile(buildSimpleProfile("c"));
        manager.activate("a");
        manager.activate("b");
        manager.activate("c");

        manager.emergencyStop();

        assertTrue(manager.getActiveProfileNames().isEmpty());
    }

    // ===== Status =====

    @Test
    public void testGetStatusEmpty() {
        assertTrue(manager.getStatus().isEmpty());
    }

    @Test
    public void testGetStatusWithProfiles() {
        manager.registerProfile(buildSimpleProfile("active-one"));
        manager.registerProfile(buildSimpleProfile("inactive-one"));
        manager.activate("active-one");

        List<ChaosManager.ProfileStatus> statusList = manager.getStatus();

        assertEquals(2, statusList.size());
        for (ChaosManager.ProfileStatus status : statusList) {
            if (status.getName().equals("active-one")) {
                assertTrue(status.isActive());
            } else {
                assertFalse(status.isActive());
            }
        }
    }

    @Test
    public void testStatusIncludesRuleCount() {
        manager.registerProfile(buildMultiRuleProfile("test", 3));

        List<ChaosManager.ProfileStatus> statusList = manager.getStatus();
        ChaosManager.ProfileStatus status = statusList.get(0);
        assertEquals(3, status.getRuleCount());
    }

    @Test
    public void testStatusIncludesEnvironments() {
        ChaosProfile profile = buildSimpleProfile("test");
        profile.setEnvironments(Arrays.asList("ft-1", "ft-2"));
        manager.registerProfile(profile);

        List<ChaosManager.ProfileStatus> statusList = manager.getStatus();
        assertEquals(Arrays.asList("ft-1", "ft-2"), statusList.get(0).getEnvironments());
    }

    @Test
    public void testGetActiveProfileNames() {
        manager.registerProfile(buildSimpleProfile("a"));
        manager.registerProfile(buildSimpleProfile("b"));
        manager.activate("a");

        List<String> active = manager.getActiveProfileNames();
        assertEquals(1, active.size());
        assertEquals("a", active.get(0));
    }

    // ===== Helpers =====

    private ChaosProfile buildSimpleProfile(String name) {
        ChaosProfile profile = new ChaosProfile();
        profile.setName(name);
        profile.setDescription("Test profile: " + name);
        profile.setEnvironments(Collections.<String>emptyList());
        profile.setRules(Collections.singletonList(buildChaosRule(
                name + "-rule", "GET", "/api/test")));
        return profile;
    }

    private ChaosProfile buildMultiRuleProfile(String name, int ruleCount) {
        ChaosProfile profile = new ChaosProfile();
        profile.setName(name);
        List<ChaosRule> rules = new ArrayList<ChaosRule>();
        for (int i = 0; i < ruleCount; i++) {
            rules.add(buildChaosRule(name + "-rule-" + i, "GET", "/api/" + name + "/" + i));
        }
        profile.setRules(rules);
        return profile;
    }

    private ChaosRule buildChaosRule(String name, String method, String path) {
        ChaosRule rule = new ChaosRule();
        rule.setName(name);
        rule.setMethod(method);
        rule.setPath(path);

        Fault fault = new Fault();
        fault.setType("HTTP_ERROR");
        fault.setProbability(0.5);
        fault.setStatusCodes(Arrays.asList(503, 504));

        FaultInjection fi = new FaultInjection();
        fi.setFaults(Collections.singletonList(fault));
        rule.setFaultInjection(fi);

        return rule;
    }
}
