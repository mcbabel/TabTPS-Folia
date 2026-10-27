/*
 * This file is part of TabTPS, licensed under the MIT License.
 *
 * Copyright (c) 2020-2024 Jason Penilla
 * Copyright (c) 2026 MCbabel (Folia support)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package xyz.jpenilla.tabtps.folia.service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.bukkit.Bukkit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.jpenilla.tabtps.common.service.TickTimeService;

public final class SpigotTickTimeService implements TickTimeService {
  private static final Logger LOGGER = LoggerFactory.getLogger(SpigotTickTimeService.class);
  private static final double IDLE_TPS = 20.0;
  private static final int TICK_TIMES_LENGTH = 100;

  private final @Nullable Object minecraftServer;
  private final @Nullable Field recentTps;
  private final @Nullable Method averageTickTimeNanos;
  private final @Nullable Field tickTimes;

  public SpigotTickTimeService() {
    Object server = null;
    Field tps = null;
    Method mspt = null;
    Field times = null;
    try {
      server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
      tps = server.getClass().getField("recentTps");
      mspt = averageTickTimeNanos(server.getClass());
      if (mspt == null) {
        times = tickTimes(server);
      }
    } catch (final Throwable t) {
      LOGGER.warn("Failed to access server tick data, TPS/MSPT displays will show fallback values", t);
      server = null;
    }
    this.minecraftServer = server;
    this.recentTps = tps;
    this.averageTickTimeNanos = mspt;
    this.tickTimes = times;
  }

  private static @Nullable Method averageTickTimeNanos(final Class<?> serverClass) {
    try {
      return serverClass.getMethod("getAverageTickTimeNanos");
    } catch (final NoSuchMethodException e) {
      return null;
    }
  }

  private static @Nullable Field tickTimes(final Object server) throws IllegalAccessException {
    for (Class<?> type = server.getClass(); type != null; type = type.getSuperclass()) {
      for (final Field field : type.getDeclaredFields()) {
        if (field.getType() != long[].class || Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        field.setAccessible(true);
        final long[] values = (long[]) field.get(server);
        if (values != null && values.length == TICK_TIMES_LENGTH) {
          return field;
        }
      }
    }
    return null;
  }

  @Override
  public double averageMspt() {
    if (this.minecraftServer == null) {
      return 0;
    }
    try {
      if (this.averageTickTimeNanos != null) {
        return (Long) this.averageTickTimeNanos.invoke(this.minecraftServer) / 1.0E6D;
      }
      if (this.tickTimes != null) {
        long sum = 0;
        for (final long time : (long[]) this.tickTimes.get(this.minecraftServer)) {
          sum += time;
        }
        return sum / (double) TICK_TIMES_LENGTH / 1.0E6D;
      }
    } catch (final ReflectiveOperationException ignored) {
    }
    return 0;
  }

  @Override
  public double @NonNull [] recentTps() {
    if (this.minecraftServer != null && this.recentTps != null) {
      try {
        return ((double[]) this.recentTps.get(this.minecraftServer)).clone();
      } catch (final ReflectiveOperationException ignored) {
      }
    }
    return new double[]{IDLE_TPS, IDLE_TPS, IDLE_TPS};
  }
}
