package io.hyperfoil.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.hyperfoil.api.session.ResourceUtilizer;

public class CollectingVisitorTest {
   @Test
   public void primitivePayloadsDoNotIncreaseResourceDiscoveryWork() {
      Object[] payloads = {
            new byte[1024 * 1024], new boolean[1024], new char[1024], new short[1024],
            new int[1024], new long[1024], new float[1024], new double[1024]
      };
      ResourceUtilizer resource = session -> {
      };
      for (Object payload : payloads) {
         ResourceCollector visitor = new ResourceCollector();
         visitor.visit(new Object[] { payload, resource });
         assertEquals(List.of(resource), visitor.resources);
      }
   }

   @Test
   public void referenceArraysStillExposeResourcesAndHandleCycles() {
      ResourceUtilizer first = session -> {
      };
      ResourceUtilizer second = session -> {
      };
      Object[] root = new Object[3];
      root[0] = new Object[] { first, new byte[1024] };
      root[1] = new ResourceUtilizer[] { second, first };
      root[2] = root;

      ResourceCollector visitor = new ResourceCollector();
      visitor.visit(root);

      assertEquals(List.of(first, second), visitor.resources);
   }

   @Test
   public void explicitlyRequestedArrayTypeIsStillCollected() {
      byte[] payload = new byte[1024];
      List<byte[]> collected = new ArrayList<>();
      new CollectingVisitor<byte[]>(byte[].class) {
         @Override
         protected boolean process(byte[] value) {
            collected.add(value);
            return false;
         }
      }.visit(new Object[] { payload, payload });

      assertEquals(1, collected.size());
      assertSame(payload, collected.get(0));
   }

   private static class ResourceCollector extends CollectingVisitor<ResourceUtilizer> {
      final List<ResourceUtilizer> resources = new ArrayList<>();
      int visits;

      ResourceCollector() {
         super(ResourceUtilizer.class);
      }

      @Override
      public boolean visit(String name, Object value, Type fieldType) {
         // Fail promptly if discovery walks payload elements, without relying on timing or exhausting the test JVM's heap.
         assertTrue(++visits <= 64, "Resource discovery must not scale with the number of primitive payload elements");
         return super.visit(name, value, fieldType);
      }

      @Override
      protected boolean process(ResourceUtilizer value) {
         resources.add(value);
         return false;
      }
   }
}
