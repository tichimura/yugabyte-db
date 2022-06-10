/*
 * Copyright 2022 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.NodeUniverseManager;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.common.TestUtils;
import com.yugabyte.yw.common.audit.AuditService;
import com.yugabyte.yw.controllers.handlers.UniversePerfHandler;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import junitparams.JUnitParamsRunner;
import kamon.instrumentation.play.GuiceModule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.modules.swagger.SwaggerModule;
import play.mvc.Result;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static com.yugabyte.yw.common.TestHelper.testDatabase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static play.inject.Bindings.bind;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.contentAsString;

@RunWith(JUnitParamsRunner.class)
public class UniversePerfControllerTest extends FakeDBApplication {
  @Rule public MockitoRule rule = MockitoJUnit.rule();

  private UniversePerfController universePerfController;
  private UniversePerfHandler universePerfHandler;
  private AuditService auditService;
  private NodeUniverseManager mockNodeUniverseManager = mock(NodeUniverseManager.class);
  private Customer customer;
  private Universe universe;
  private OffsetDateTime mockedTime = OffsetDateTime.now();
  private List<ShellResponse> shellResponses = new ArrayList<>();

  @Override
  protected Application provideApplication() {
    mockNodeUniverseManager = mock(NodeUniverseManager.class);
    universePerfHandler = spy(new TestUniversePerfHandler(mockNodeUniverseManager));
    universePerfController = spy(new UniversePerfController(universePerfHandler));

    return configureApplication(
            new GuiceApplicationBuilder()
                .disable(SwaggerModule.class)
                .disable(GuiceModule.class)
                .configure(testDatabase())
                .overrides(bind(NodeUniverseManager.class).toInstance(mockNodeUniverseManager)))
        .build();
  }

  @Before
  public void setUp() {
    auditService = new AuditService();
    universePerfController.setAuditService(auditService);

    // Parse different shell responses and populate in shellResponses list
    for (int i = 1; i <= 7; i++) {
      ShellResponse shellResponse =
          ShellResponse.create(
              ShellResponse.ERROR_CODE_SUCCESS,
              TestUtils.readResource(
                  "com/yugabyte/yw/controllers/universe_performance_advisor/query_distribution_shell_response_"
                      + i
                      + ".txt"));
      shellResponses.add(shellResponse);
    }
    customer = ModelFactory.testCustomer();
    universe = createUniverse(customer.getCustomerId());
    universe = Universe.saveDetails(universe.universeUUID, ApiUtils.mockUniverseUpdater());
  }

  @Test
  public void testQueryDistributionNoSuggestion1() {
    // Test case where all nodes of universe have equal query load, and hence no query distribution
    // related suggestions should be given.
    when(mockNodeUniverseManager.runYsqlCommand(anyObject(), anyObject(), anyObject(), anyObject()))
        .thenReturn(shellResponses.get(0));

    Result queryDistributionSuggestions =
        universePerfController.getQueryDistributionSuggestions(
            customer.uuid, universe.universeUUID);
    JsonNode json = Json.parse(contentAsString(queryDistributionSuggestions));
    assertEquals(OK, queryDistributionSuggestions.status());
    assertTrue(json.isObject());
    assertNull(json.get("suggestion"));
    assertNull(json.get("description"));
    assertNull(json.get("startTime"));
    assertNotNull(json.get("endTime"));
    assertNotNull(json.get("details"));
    Iterator<JsonNode> details = json.get("details").iterator();
    while (details.hasNext()) {
      JsonNode detail = details.next();
      assertNotNull(detail.get("node").asText());
      assertEquals(detail.get("numSelect").asInt(), 1000);
      assertEquals(detail.get("numInsert").asInt(), 0);
      assertEquals(detail.get("numUpdate").asInt(), 0);
      assertEquals(detail.get("numDelete").asInt(), 0);
    }
  }

  @Test
  public void testQueryDistributionNoSuggestion2() {
    // Test case where suggestion would not be given because of minimum query count threshold
    // criteria (MINIMUM_TOTAL_QUERY_THRESHOLD_FOR_SUGGESTIONS=1000) not being satisfied.
    when(mockNodeUniverseManager.runYsqlCommand(anyObject(), anyObject(), anyObject(), anyObject()))
        .thenReturn(shellResponses.get(2), shellResponses.get(1));

    Result queryDistributionSuggestions =
        universePerfController.getQueryDistributionSuggestions(
            customer.uuid, universe.universeUUID);
    JsonNode json = Json.parse(contentAsString(queryDistributionSuggestions));
    assertEquals(OK, queryDistributionSuggestions.status());
    assertTrue(json.isObject());
    assertNull(json.get("suggestion"));
    assertNull(json.get("description"));
    assertNull(json.get("startTime"));
    assertNotNull(json.get("endTime"));
    assertNotNull(json.get("details"));
    Iterator<JsonNode> details = json.get("details").iterator();

    int numNodesWithNumSelect999 = 0;
    int numNodesWithNumSelect0 = 0;
    while (details.hasNext()) {
      JsonNode detail = details.next();
      if (detail.get("numSelect").asInt() == 0) {
        numNodesWithNumSelect0++;
      } else if (detail.get("numSelect").asInt() == 999) {
        numNodesWithNumSelect999++;
      }
      assertNotNull(detail.get("node").asText());
      assertEquals(detail.get("numInsert").asInt(), 0);
      assertEquals(detail.get("numUpdate").asInt(), 0);
      assertEquals(detail.get("numDelete").asInt(), 0);
    }
    assertEquals(numNodesWithNumSelect999, 1);
    assertEquals(numNodesWithNumSelect0, 2);
  }

  @Test
  public void testQueryDistributionNoSuggestion3() {
    // Test case where one of the nodes was overloaded more than 1 hours back, but no node is
    // overloaded in last 1 hour, and hence no suggestions.

    // Return 1000 numSelect for 1 node, 0 for remaining nodes
    when(mockNodeUniverseManager.runYsqlCommand(anyObject(), anyObject(), anyObject(), anyObject()))
        .thenReturn(shellResponses.get(0), shellResponses.get(1));

    Result queryDistributionSuggestions =
        universePerfController.getQueryDistributionSuggestions(
            customer.uuid, universe.universeUUID);
    JsonNode json = Json.parse(contentAsString(queryDistributionSuggestions));
    assertEquals(OK, queryDistributionSuggestions.status());
    assertTrue(json.isObject());

    assertNull(json.get("startTime"));
    assertNotNull(json.get("endTime"));
    assertNotNull(json.get("details"));
    Iterator<JsonNode> details = json.get("details").elements();

    int numNodesWithNumSelect1000 = 0;
    int numNodesWithNumSelect0 = 0;
    String nodeWithHeavyQueryLoad = null;
    while (details.hasNext()) {
      JsonNode detail = details.next();
      if (detail.get("numSelect").asInt() == 0) {
        numNodesWithNumSelect0++;
      } else if (detail.get("numSelect").asInt() == 1000) {
        nodeWithHeavyQueryLoad = detail.get("node").asText();
        numNodesWithNumSelect1000++;
      }
      assertNotNull(detail.get("node").asText());
      assertEquals(detail.get("numInsert").asInt(), 0);
      assertEquals(detail.get("numUpdate").asInt(), 0);
      assertEquals(detail.get("numDelete").asInt(), 0);
    }
    assertEquals(numNodesWithNumSelect1000, 1);
    assertEquals(numNodesWithNumSelect0, 2);

    assertTrue(json.get("description").asText().contains(nodeWithHeavyQueryLoad));
    assertEquals(
        json.get("suggestion").asText(), "Redistribute queries to other nodes in the cluster");

    // Go 2 hours ahead in time.
    mockedTime = mockedTime.plusHours(2);

    // Mock the query response such that all nodes had no load in last 2 hours.
    when(mockNodeUniverseManager.runYsqlCommand(anyObject(), anyObject(), anyObject(), anyObject()))
        .thenReturn(shellResponses.get(3));
    queryDistributionSuggestions =
        universePerfController.getQueryDistributionSuggestions(
            customer.uuid, universe.universeUUID);
    json = Json.parse(contentAsString(queryDistributionSuggestions));
    assertEquals(OK, queryDistributionSuggestions.status());
    assertTrue(json.isObject());

    assertNull(json.get("description"));
    assertNull(json.get("suggestion"));
    assertNotNull(json.get("startTime"));
    assertNotNull(json.get("endTime"));
    assertNotNull(json.get("details"));
  }

  @Test
  public void testQueryDistributionWithSuggestion1() {
    // Test case when one node is overloaded.

    // Return 1000 numSelect for 1 node, 0 for remaining nodes
    when(mockNodeUniverseManager.runYsqlCommand(anyObject(), anyObject(), anyObject(), anyObject()))
        .thenReturn(shellResponses.get(0), shellResponses.get(1));

    Result queryDistributionSuggestions =
        universePerfController.getQueryDistributionSuggestions(
            customer.uuid, universe.universeUUID);
    JsonNode json = Json.parse(contentAsString(queryDistributionSuggestions));
    assertEquals(OK, queryDistributionSuggestions.status());
    assertTrue(json.isObject());

    assertNull(json.get("startTime"));
    assertNotNull(json.get("endTime"));
    assertNotNull(json.get("details"));
    Iterator<JsonNode> details = json.get("details").elements();

    int numNodesWithNumSelect1000 = 0;
    int numNodesWithNumSelect0 = 0;
    String nodeWithHeavyQueryLoad = null;
    while (details.hasNext()) {
      JsonNode detail = details.next();
      if (detail.get("numSelect").asInt() == 0) {
        numNodesWithNumSelect0++;
      } else if (detail.get("numSelect").asInt() == 1000) {
        nodeWithHeavyQueryLoad = detail.get("node").asText();
        numNodesWithNumSelect1000++;
      }
      assertNotNull(detail.get("node").asText());
      assertEquals(detail.get("numInsert").asInt(), 0);
      assertEquals(detail.get("numUpdate").asInt(), 0);
      assertEquals(detail.get("numDelete").asInt(), 0);
    }
    assertEquals(numNodesWithNumSelect1000, 1);
    assertEquals(numNodesWithNumSelect0, 2);

    assertTrue(json.get("description").asText().contains(nodeWithHeavyQueryLoad));
    assertEquals(
        json.get("suggestion").asText(), "Redistribute queries to other nodes in the cluster");
  }

  @Test
  public void testQueryDistributionWithSuggestion2() {
    // Test case where multiple nodes are overloaded, and the most overloaded node is reported.

    // Return 1000 numSelect for 1 node, 0 for remaining nodes
    when(mockNodeUniverseManager.runYsqlCommand(anyObject(), anyObject(), anyObject(), anyObject()))
        .thenReturn(shellResponses.get(0), shellResponses.get(4), shellResponses.get(5));

    Result queryDistributionSuggestions =
        universePerfController.getQueryDistributionSuggestions(
            customer.uuid, universe.universeUUID);
    JsonNode json = Json.parse(contentAsString(queryDistributionSuggestions));
    assertEquals(OK, queryDistributionSuggestions.status());
    assertTrue(json.isObject());

    assertNull(json.get("startTime"));
    assertNotNull(json.get("endTime"));
    assertNotNull(json.get("details"));
    Iterator<JsonNode> details = json.get("details").elements();

    int numNodesWithNumSelect1000 = 0;
    String nodeWithHeavyQueryLoad = null;
    while (details.hasNext()) {
      JsonNode detail = details.next();
      if (detail.get("numSelect").asInt() == 1000) {
        nodeWithHeavyQueryLoad = detail.get("node").asText();
        numNodesWithNumSelect1000++;
      }
      assertNotNull(detail.get("node").asText());
      assertEquals(detail.get("numInsert").asInt(), 0);
      assertEquals(detail.get("numUpdate").asInt(), 0);
      assertEquals(detail.get("numDelete").asInt(), 0);
    }
    assertEquals(numNodesWithNumSelect1000, 1);

    assertTrue(json.get("description").asText().contains(nodeWithHeavyQueryLoad));
    assertEquals(
        json.get("description").asText(),
        "Node "
            + nodeWithHeavyQueryLoad
            + " processed 233.33% more queries than average of other 2 nodes.");
    assertEquals(
        json.get("suggestion").asText(), "Redistribute queries to other nodes in the cluster");
  }

  @Test
  public void testQueryDistributionWithSuggestion3() {
    // Test case when one node is overloaded. This is similar to
    // testQueryDistributionWithSuggestion1, but tests different queries instead of only select.

    // Return 100 numSelect, 200 numDelete, 300 numInsert, 400 numUpdate queries for 1 node. 0 for
    // remaining nodes.
    when(mockNodeUniverseManager.runYsqlCommand(anyObject(), anyObject(), anyObject(), anyObject()))
        .thenReturn(shellResponses.get(6), shellResponses.get(1));

    Result queryDistributionSuggestions =
        universePerfController.getQueryDistributionSuggestions(
            customer.uuid, universe.universeUUID);
    JsonNode json = Json.parse(contentAsString(queryDistributionSuggestions));
    assertEquals(OK, queryDistributionSuggestions.status());
    assertTrue(json.isObject());

    assertNull(json.get("startTime"));
    assertNotNull(json.get("endTime"));
    assertNotNull(json.get("details"));
    Iterator<JsonNode> details = json.get("details").elements();

    int numNodesWithNumSelect100 = 0;
    String nodeWithHeavyQueryLoad = null;
    while (details.hasNext()) {
      JsonNode detail = details.next();
      if (detail.get("numSelect").asInt() == 100) {
        numNodesWithNumSelect100++;
        assertEquals(detail.get("numDelete").asInt(), 200);
        assertEquals(detail.get("numInsert").asInt(), 300);
        assertEquals(detail.get("numUpdate").asInt(), 400);
        nodeWithHeavyQueryLoad = detail.get("node").asText();
      } else {
        assertEquals(detail.get("numSelect").asInt(), 0);
        assertEquals(detail.get("numInsert").asInt(), 0);
        assertEquals(detail.get("numUpdate").asInt(), 0);
        assertEquals(detail.get("numDelete").asInt(), 0);
      }
      assertNotNull(detail.get("node").asText());
    }

    assertEquals(numNodesWithNumSelect100, 1);

    assertTrue(json.get("description").asText().contains(nodeWithHeavyQueryLoad));
    assertEquals(
        json.get("suggestion").asText(), "Redistribute queries to other nodes in the cluster");
  }

  private class TestUniversePerfHandler extends UniversePerfHandler {

    public TestUniversePerfHandler(NodeUniverseManager nodeUniverseManager) {
      super(nodeUniverseManager);
    }

    @Override
    public OffsetDateTime getCurrentOffsetDateTime() {
      return mockedTime;
    }
  }
}
