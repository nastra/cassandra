/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.guardrails;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.service.ClientWarn;
import org.assertj.core.api.Assertions;

import static java.lang.String.format;
import static org.apache.cassandra.guardrails.Guardrail.DisableFlag;
import static org.apache.cassandra.guardrails.Guardrail.DisallowedValues;
import static org.apache.cassandra.guardrails.Guardrail.Threshold;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GuardrailsTest
{
    // Save whether guardrails where enabled or not at the beginning of this class to restore the value at the end since
    // the tests here mess with this value.
    private static boolean guardrailEnabledInitialState;

    @BeforeClass
    public static void setup()
    {
        DatabaseDescriptor.daemonInitialization();
        guardrailEnabledInitialState = DatabaseDescriptor.getGuardrailsConfig().enabled;
    }

    @AfterClass
    public static void tearDown()
    {
        DatabaseDescriptor.getGuardrailsConfig().enabled = guardrailEnabledInitialState;
    }

    private TriggerCollector createAndAddCollector()
    {
        TriggerCollector collector = new TriggerCollector();
        Guardrails.register(collector);
        return collector;
    }

    private void assertWarn(Runnable runnable, String fullMessage, String redactedMessage)
    {
        // We use client warnings and listeners to check we properly warn as this is the most convenient. Technically,
        // this doesn't validate we also log the warning, but that's probably fine ...
        ClientWarn.instance.captureWarnings();
        TriggerCollector collector = createAndAddCollector();
        try
        {
            runnable.run();

            // Client Warnings
            List<String> warnings = ClientWarn.instance.getWarnings();
            assertThat(warnings.isEmpty()).isFalse();
            assertThat(warnings.size()).isEqualTo(1);
            String warning = warnings.get(0);
            assertThat(warning).contains(fullMessage);

            // Listeners
            assertThat(collector.failuresTriggered).isEmpty();
            assertThat(collector.warningsTriggered).isNotEmpty();
            assertThat(collector.warningsTriggered.size()).isEqualTo(1);

            assertThat(collector.warningsTriggered.containsValue(redactedMessage)).isTrue();
        }
        finally
        {
            ClientWarn.instance.resetWarnings();
            Guardrails.unregister(collector);
        }
    }

    private void assertWarn(Runnable runnable, String message)
    {
        assertWarn(runnable, message, message);
    }

    private void assertFails(Runnable runnable, String fullMessage)
    {
        assertFails(runnable, fullMessage, fullMessage, true);
    }

    private void assertFails(Runnable runnable, String fullMessage, String redactedMessage)
    {
        assertFails(runnable, fullMessage, redactedMessage, true);
    }

    private void assertFails(Runnable runnable, String fullMessage, String redactedMessage, boolean notified)
    {
        TriggerCollector collector = createAndAddCollector();

        try
        {
            assertThatThrownBy(runnable::run)
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining(fullMessage);

            // Listeners
            if (notified)
            {

                assertThat(collector.failuresTriggered).isNotEmpty();
                assertThat(collector.warningsTriggered).isEmpty();
                assertThat(collector.failuresTriggered.size()).isEqualTo(1);
                assertThat(collector.failuresTriggered.containsValue(redactedMessage)).isTrue();
            }
            else
            {
                assertThat(collector.failuresTriggered).isEmpty();
            }
        }
        finally
        {
            Guardrails.unregister(collector);
        }
    }

    private void assertNoWarnOrFails(Runnable runnable)
    {
        ClientWarn.instance.captureWarnings();
        try
        {
            runnable.run();
            List<String> warnings = ClientWarn.instance.getWarnings();
            if (warnings == null) // will always be the case in practice currently, but being defensive if this change
                warnings = Collections.emptyList();
            assertThat(warnings).isEmpty();
        }
        catch (InvalidRequestException e)
        {
            Assertions.fail("Expected not to fail, but failed with error message: " + e.getMessage());
        }
        finally
        {
            ClientWarn.instance.resetWarnings();
        }
    }

    @Test
    public void testDisabledThreshold()
    {
        DatabaseDescriptor.getGuardrailsConfig().enabled = false;

        Threshold.ErrorMessageProvider errorMessageProvider = (isWarn, what, v, t) -> "Should never trigger";
        testDisabledThreshold(new Threshold("a", () -> 10, () -> 100, errorMessageProvider));
        testDisabledThreshold(new Threshold("b", () -> 10, () -> -1, errorMessageProvider));
        testDisabledThreshold(new Threshold("c", () -> -1, () -> 100, errorMessageProvider));
        testDisabledThreshold(new Threshold("d", () -> -1, () -> -1, errorMessageProvider));
        testDisabledThreshold(new Threshold("e", () -> -1, () -> -1, errorMessageProvider));

        DatabaseDescriptor.getGuardrailsConfig().enabled = true;
        testDisabledThreshold(new Threshold("e", () -> -1, () -> -1, errorMessageProvider));
    }

    private void testDisabledThreshold(Threshold guard)
    {
        assertThat(guard.enabled()).isFalse();

        assertThat(guard.triggersOn(1)).isFalse();
        assertThat(guard.triggersOn(10)).isFalse();
        assertThat(guard.triggersOn(11)).isFalse();
        assertThat(guard.triggersOn(50)).isFalse();
        assertThat(guard.triggersOn(110)).isFalse();

        for (Boolean containsUserData : Arrays.asList(true, false))
        {
            assertNoWarnOrFails(() -> guard.guard(5, "Z", containsUserData));
            assertNoWarnOrFails(() -> guard.guard(25, "A", containsUserData));
            assertNoWarnOrFails(() -> guard.guard(100, "B", containsUserData));
            assertNoWarnOrFails(() -> guard.guard(101, "X", containsUserData));
            assertNoWarnOrFails(() -> guard.guard(200, "Y", containsUserData));
        }
    }

    @Test
    public void testThreshold()
    {
        DatabaseDescriptor.getGuardrailsConfig().enabled = true;

        Threshold guard = new Threshold("x",
                                        () -> 10,
                                        () -> 100,
                                        (isWarn, what, v, t) -> format("%s: for %s, %s > %s",
                                                                       isWarn ? "Warning" : "Failure", what, v, t));

        assertThat(guard.enabled()).isTrue();
        assertThat(guard.triggersOn(1)).isFalse();
        assertThat(guard.triggersOn(10)).isFalse();
        assertThat(guard.triggersOn(11)).isTrue();
        assertThat(guard.triggersOn(50)).isTrue();
        assertThat(guard.triggersOn(110)).isTrue();

        assertNoWarnOrFails(() -> guard.guard(5, "Z"));
        assertNoWarnOrFails(() -> guard.guard(5, "Z", true));

        assertWarn(() -> guard.guard(25, "A"), "Warning: for A, 25 > 10");
        assertWarn(() -> guard.guard(25, "A", true),
                   "Warning: for A, 25 > 10", "Warning: for <redacted>, 25 > 10");

        assertWarn(() -> guard.guard(100, "B"), "Warning: for B, 100 > 10");
        assertWarn(() -> guard.guard(100, "B", true),
                   "Warning: for B, 100 > 10", "Warning: for <redacted>, 100 > 10");

        assertFails(() -> guard.guard(101, "X"), "Failure: for X, 101 > 100");
        assertFails(() -> guard.guard(101, "X", true),
                    "Failure: for X, 101 > 100", "Failure: for <redacted>, 101 > 100");

        assertFails(() -> guard.guard(200, "Y"), "Failure: for Y, 200 > 100");
        assertFails(() -> guard.guard(200, "Y", true),
                    "Failure: for Y, 200 > 100", "Failure: for <redacted>, 200 > 100");
    }

    @Test
    public void testWarnOnlyThreshold()
    {
        DatabaseDescriptor.getGuardrailsConfig().enabled = true;

        Threshold guard = new Threshold("x",
                                        () -> 10,
                                        () -> -1L,
                                        (isWarn, what, v, t) -> format("%s: for %s, %s > %s",
                                                                       isWarn ? "Warning" : "Failure", what, v, t));

        assertThat(guard.enabled()).isTrue();
        assertThat(guard.triggersOn(10)).isFalse();
        assertThat(guard.triggersOn(11)).isTrue();

        assertNoWarnOrFails(() -> guard.guard(5, "Z"));
        assertNoWarnOrFails(() -> guard.guard(5, "Z", true));

        assertWarn(() -> guard.guard(11, "A"), "Warning: for A, 11 > 10");
        assertWarn(() -> guard.guard(11, "A", true), "Warning: for A, 11 > 10", "Warning: for <redacted>, 11 > 10");
    }

    @Test
    public void testFailureOnlyThreshold()
    {
        DatabaseDescriptor.getGuardrailsConfig().enabled = true;

        Threshold guard = new Threshold("x",
                                        () -> -1L,
                                        () -> 10,
                                        (isWarn, what, v, t) -> format("%s: for %s, %s > %s",
                                                                       isWarn ? "Warning" : "Failure", what, v, t));

        assertThat(guard.enabled()).isTrue();
        assertThat(guard.triggersOn(10)).isFalse();
        assertThat(guard.triggersOn(11)).isTrue();

        assertNoWarnOrFails(() -> guard.guard(5, "Z"));
        assertNoWarnOrFails(() -> guard.guard(5, "Z", true));

        assertFails(() -> guard.guard(11, "A"), "Failure: for A, 11 > 10");
        assertFails(() -> guard.guard(11, "A", true), "Failure: for A, 11 > 10", "Failure: for <redacted>, 11 > 10");
    }

    @Test
    public void testDisabledDisableFlag()
    {
        DatabaseDescriptor.getGuardrailsConfig().enabled = false;

        assertNoWarnOrFails(new DisableFlag("x", () -> true, "X")::ensureEnabled);
        assertNoWarnOrFails(new DisableFlag("x", () -> false, "X")::ensureEnabled);
    }

    @Test
    public void testDisableFlag()
    {
        DatabaseDescriptor.getGuardrailsConfig().enabled = true;

        assertFails(new DisableFlag("x", () -> true, "X")::ensureEnabled, "X is not allowed");
        assertNoWarnOrFails(new DisableFlag("x", () -> false, "X")::ensureEnabled);
    }

    @Test
    public void testDisabledDisallowedValues()
    {
        DatabaseDescriptor.getGuardrailsConfig().enabled = false;

        DisallowedValues<Integer> disallowed = new DisallowedValues<>("x",
                                                                      () -> new HashSet<>(Arrays.asList("4", "6", "20")),
                                                                      Integer::valueOf,
                                                                      "integer");

        assertNoWarnOrFails(() -> disallowed.ensureAllowed(3));
        assertNoWarnOrFails(() -> disallowed.ensureAllowed(4));
        assertNoWarnOrFails(() -> disallowed.ensureAllowed(10));
        assertNoWarnOrFails(() -> disallowed.ensureAllowed(20));
        assertNoWarnOrFails(() -> disallowed.ensureAllowed(200));
    }

    @Test
    public void testDisallowedValues()
    {
        DatabaseDescriptor.getGuardrailsConfig().enabled = true;

        // Using a LinkedHashSet below to ensure the order in the error message checked below are not random
        DisallowedValues<Integer> disallowed = new DisallowedValues<>(
        "x",
        () -> new LinkedHashSet<>(Arrays.asList("4", "6", "20")),
        Integer::valueOf,
        "integer");

        assertNoWarnOrFails(() -> disallowed.ensureAllowed(3));
        assertFails(() -> disallowed.ensureAllowed(4),
                    "Provided value 4 is not allowed for integer (disallowed values are: [4, 6, 20])");
        assertNoWarnOrFails(() -> disallowed.ensureAllowed(10));
        assertFails(() -> disallowed.ensureAllowed(20),
                    "Provided value 20 is not allowed for integer (disallowed values are: [4, 6, 20])");
        assertNoWarnOrFails(() -> disallowed.ensureAllowed(200));
        assertNoWarnOrFails(() -> disallowed.ensureAllowed(set(1, 2, 3)));

        assertFails(() -> disallowed.ensureAllowed(set(4, 6)),
                    "Provided values [4, 6] are not allowed for integer (disallowed values are: [4, 6, 20])");
        assertFails(() -> disallowed.ensureAllowed(set(4, 5, 6, 7)),
                    "Provided values [4, 6] are not allowed for integer (disallowed values are: [4, 6, 20])");
    }

    private Set<Integer> set(Integer... values)
    {
        return new HashSet<>(Arrays.asList(values));
    }

    private static class TriggerCollector implements Guardrails.Listener
    {
        final Map<String, String> warningsTriggered = new HashMap<>();
        final Map<String, String> failuresTriggered = new HashMap<>();

        @Override
        public void onWarningTriggered(String guardrailName, String message)
        {
            warningsTriggered.put(guardrailName, message);
        }

        @Override
        public void onFailureTriggered(String guardrailName, String message)
        {
            failuresTriggered.put(guardrailName, message);
        }
    }
}