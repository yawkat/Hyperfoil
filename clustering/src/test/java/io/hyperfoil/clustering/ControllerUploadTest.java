package io.hyperfoil.clustering;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import io.hyperfoil.client.RestClient;
import io.hyperfoil.client.RestClientException;
import io.hyperfoil.internal.Properties;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.BodyHandler;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
public class ControllerUploadTest {
   @TempDir
   static Path root;
   static String previousRoot;
   String previousLimit;
   Vertx vertx;
   RestClient client;

   @BeforeAll
   public static void configureRoot() {
      previousRoot = System.setProperty(Properties.ROOT_DIR, root.toString());
   }

   @AfterAll
   public static void restoreRoot() {
      restore(Properties.ROOT_DIR, previousRoot);
   }

   @BeforeEach
   public void resetLimit() {
      previousLimit = System.getProperty(Properties.CONTROLLER_MAX_BODY_SIZE);
      System.clearProperty(Properties.CONTROLLER_MAX_BODY_SIZE);
   }

   @AfterEach
   public void closeController() throws Exception {
      try {
         if (client != null) {
            client.close();
         }
         if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
         }
      } finally {
         restore(Properties.CONTROLLER_MAX_BODY_SIZE, previousLimit);
      }
   }

   @Test
   public void registerMultipartBenchmarkAboveDefaultLimit() throws Exception {
      System.setProperty(Properties.CONTROLLER_MAX_BODY_SIZE, Integer.toString(32 * 1024 * 1024));
      startController();
      registerAndCheck((int) BodyHandler.DEFAULT_BODY_LIMIT + 1024 * 1024);
   }

   @Test
   public void defaultLimitStillRejectsOversizedUploads() throws Exception {
      startController();
      assertRejected((int) BodyHandler.DEFAULT_BODY_LIMIT + 1024 * 1024);
   }

   @Test
   public void configuredLimitRejectsOversizedUploads() throws Exception {
      System.setProperty(Properties.CONTROLLER_MAX_BODY_SIZE, Integer.toString(128 * 1024));
      startController();
      assertRejected(256 * 1024);
   }

   @Test
   public void configuredLimitAcceptsSmallerUploads() throws Exception {
      System.setProperty(Properties.CONTROLLER_MAX_BODY_SIZE, Integer.toString(128 * 1024));
      startController();
      registerAndCheck(64 * 1024);
   }

   private void startController() throws Exception {
      vertx = Vertx.vertx();
      ControllerVerticle controller = new ControllerVerticle();
      vertx.deployVerticle(controller, new DeploymentOptions().setConfig(new JsonObject()
            .put(Properties.CONTROLLER_HOST, "localhost").put(Properties.CONTROLLER_PORT, 0)))
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      client = new RestClient(vertx, "localhost", controller.actualPort(), false, false, null);
   }

   private void registerAndCheck(int size) {
      String name = "upload-" + UUID.randomUUID();
      byte[] payload = new byte[size];
      Arrays.fill(payload, (byte) 0x5a);
      payload[size - 1] = 0x7f;
      var benchmark = client.register(source(name), Map.of("payload.bin", payload), null, null);
      assertTrue(benchmark.exists());
      assertArrayEquals(payload, benchmark.files().get("payload.bin"));
   }

   private void assertRejected(int size) {
      String name = "upload-" + UUID.randomUUID();
      RestClientException exception = assertThrows(RestClientException.class,
            () -> client.register(source(name), Map.of("payload.bin", new byte[size]), null, null));
      assertTrue(exception.getMessage().contains("413"), exception::getMessage);
      assertFalse(client.benchmark(name).exists());
   }

   private static String source(String name) {
      // Keep the attachment unused: registration size limits should not depend on payload traversal during scenario building.
      return """
            name: %s
            phases:
            - test:
                atOnce:
                  users: 1
                  scenario:
                  - main:
                    - noop
            """.formatted(name);
   }

   private static void restore(String property, String value) {
      if (value == null) {
         System.clearProperty(property);
      } else {
         System.setProperty(property, value);
      }
   }
}
