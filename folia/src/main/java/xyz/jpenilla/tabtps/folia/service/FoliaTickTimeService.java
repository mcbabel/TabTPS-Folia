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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.jpenilla.tabtps.common.User;
import xyz.jpenilla.tabtps.common.service.TickTimeService;

public final class FoliaTickTimeService implements TickTimeService {
  private static final Logger LOGGER = LoggerFactory.getLogger(FoliaTickTimeService.class);

  private static final double IDLE_TPS = 20.0;
  private static final long SNAPSHOT_TTL_NANOS = TimeUnit.MILLISECONDS.toNanos(1000);

  private static final int WINDOW_5S = 0;
  private static final int WINDOW_15S = 1;
  private static final int WINDOW_1M = 2;
  private static final int WINDOW_5M = 3;
  private static final int WINDOW_15M = 4;

  final @Nullable RegionTickDataAccessor accessor;
  private volatile @Nullable Snapshot snapshot;

  public FoliaTickTimeService() {
    RegionTickDataAccessor resolved = null;
    try {
      resolved = new RegionTickDataAccessor();
      LOGGER.info("Successfully initialized Folia region tick data accessor");
    } catch (final Throwable t) {
      LOGGER.warn("Failed to initialize Folia region tick data accessor, falling back to global TPS/MSPT", t);
    }
    this.accessor = resolved;
  }

  public boolean regionDataAvailable() {
    return this.accessor != null;
  }

  @Override
  public double averageMspt() {
    final Snapshot snapshot = this.snapshot();
    return snapshot == null ? 0 : snapshot.averageMspt();
  }

  @Override
  public double @NonNull [] recentTps() {
    final Snapshot snapshot = this.snapshot();
    return snapshot == null ? new double[]{IDLE_TPS, IDLE_TPS, IDLE_TPS, IDLE_TPS} : snapshot.averageTps();
  }

  @Override
  public double averageMspt(final @Nullable User<?> user) {
    final double[] data = this.regionTickData(user);
    return data == null ? this.averageMspt() : data[1];
  }

  @Override
  public double @NonNull [] recentTps(final @Nullable User<?> user) {
    final Player player = player(user);
    final RegionTickDataAccessor accessor = this.accessor;
    final double[] regionTps = player == null || accessor == null ? null : accessor.regionTps(player);
    if (regionTps != null) {
      return new double[]{regionTps[WINDOW_5S], regionTps[WINDOW_1M], regionTps[WINDOW_5M], regionTps[WINDOW_15M]};
    }
    return this.recentTps();
  }

  @Override
  public double displayTps() {
    return this.recentTps()[0];
  }

  @Override
  public double displayTps(final @Nullable User<?> user) {
    final double[] data = this.regionTickData(user);
    return data == null ? this.displayTps() : data[0];
  }

  private double @Nullable [] regionTickData(final @Nullable User<?> user) {
    final Player player = player(user);
    final RegionTickDataAccessor accessor = this.accessor;
    if (player == null || accessor == null) {
      return null;
    }
    return accessor.regionTickData(player, WINDOW_5S);
  }

  List<Double> sortedRegionTps() {
    final Snapshot snapshot = this.snapshot();
    return snapshot == null ? Collections.emptyList() : snapshot.sortedTps(WINDOW_15S);
  }

  private @Nullable Snapshot snapshot() {
    final RegionTickDataAccessor accessor = this.accessor;
    if (accessor == null) {
      return null;
    }
    final Snapshot current = this.snapshot;
    if (fresh(current)) {
      return current;
    }
    synchronized (this) {
      final Snapshot latest = this.snapshot;
      if (fresh(latest)) {
        return latest;
      }
      final Snapshot taken = accessor.takeSnapshot();
      this.snapshot = taken;
      return taken;
    }
  }

  private static boolean fresh(final @Nullable Snapshot snapshot) {
    return snapshot != null && System.nanoTime() - snapshot.takenAt < SNAPSHOT_TTL_NANOS;
  }

  private static @Nullable Player player(final @Nullable User<?> user) {
    if (user == null) {
      return null;
    }
    final Object base = user.base();
    return base instanceof Player ? (Player) base : null;
  }

  static final class Snapshot {
    private final long takenAt;
    private final List<double[]> regionTps;
    private final List<Double> regionMspt;

    Snapshot(final long takenAt, final List<double[]> regionTps, final List<Double> regionMspt) {
      this.takenAt = takenAt;
      this.regionTps = regionTps;
      this.regionMspt = regionMspt;
    }

    double averageMspt() {
      if (this.regionMspt.isEmpty()) {
        return 0;
      }
      double sum = 0;
      for (final double mspt : this.regionMspt) {
        sum += mspt;
      }
      return sum / this.regionMspt.size();
    }

    double[] averageTps() {
      if (this.regionTps.isEmpty()) {
        return new double[]{IDLE_TPS, IDLE_TPS, IDLE_TPS, IDLE_TPS};
      }
      final int[] windows = {WINDOW_5S, WINDOW_1M, WINDOW_5M, WINDOW_15M};
      final double[] averages = new double[windows.length];
      for (int i = 0; i < windows.length; i++) {
        double sum = 0;
        for (final double[] region : this.regionTps) {
          sum += region[windows[i]];
        }
        averages[i] = sum / this.regionTps.size();
      }
      return averages;
    }

    List<Double> sortedTps(final int window) {
      final List<Double> values = new ArrayList<>(this.regionTps.size());
      for (final double[] region : this.regionTps) {
        values.add(region[window]);
      }
      Collections.sort(values);
      return values;
    }
  }

  static final class RegionTickDataAccessor {
    private static final String[] TICK_REPORT_METHODS = {
      "getTickReport5s", "getTickReport15s", "getTickReport1m", "getTickReport5m", "getTickReport15m"
    };

    private final Field regioniserField;
    private final Method computeForAllRegions;
    private final Method getRegionAt;
    private final Method getData;
    private final Method getSchedulingHandle;
    private final Method[] tickReports;
    private final Method tpsData;
    private final Method timePerTickData;
    private final Method segmentAll;
    private final Method segmentAverage;
    private final Method getCraftWorldHandle;

    RegionTickDataAccessor() throws ReflectiveOperationException {
      final Class<?> craftWorldClass = Class.forName(Bukkit.getServer().getClass().getPackage().getName() + ".CraftWorld");
      this.getCraftWorldHandle = craftWorldClass.getMethod("getHandle");
      final Class<?> serverLevelClass = this.getCraftWorldHandle.getReturnType();

      Field regField = null;
      for (final Field f : serverLevelClass.getDeclaredFields()) {
        if (f.getType().getSimpleName().contains("ThreadedRegionizer")) {
          f.setAccessible(true);
          regField = f;
          break;
        }
      }
      if (regField == null) {
        throw new NoSuchFieldException("Could not find ThreadedRegionizer field on ServerLevel. Fields: " + dumpFields(serverLevelClass));
      }
      this.regioniserField = regField;

      final Class<?> regionizerClass = regField.getType();
      this.computeForAllRegions = firstMethod(regionizerClass, 1, "computeForAllRegions", "computeForAllRegionsUnsynchronised");
      if (this.computeForAllRegions == null) {
        throw new NoSuchMethodException("Could not find computeForAllRegions on " + regionizerClass.getName() + ". Methods: " + dumpMethods(regionizerClass));
      }
      this.getRegionAt = firstMethod(regionizerClass, 2, "getRegionAtSynchronised", "getRegionAtUnsynchronised");
      if (this.getRegionAt == null) {
        throw new NoSuchMethodException("Could not find getRegionAt on " + regionizerClass.getName() + ". Methods: " + dumpMethods(regionizerClass));
      }


      final Class<?> threadedRegionClass = Class.forName("io.papermc.paper.threadedregions.ThreadedRegionizer$ThreadedRegion");
      final Method getDataMethod = firstMethod(threadedRegionClass, 0, "getData");
      if (getDataMethod == null) {
        throw new NoSuchMethodException("Could not find getData on ThreadedRegion. Methods: " + dumpMethods(threadedRegionClass));
      }
      this.getData = getDataMethod;

      final Class<?> regionDataClass = Class.forName("io.papermc.paper.threadedregions.TickRegions$TickRegionData");
      Method handleMethod = firstMethod(regionDataClass, 0, "getRegionSchedulingHandle");
      if (handleMethod == null) {
        for (final Method m : regionDataClass.getDeclaredMethods()) {
          final String retName = m.getReturnType().getSimpleName();
          if (m.getParameterCount() == 0 && (retName.contains("ScheduleHandle") || retName.contains("RegionSchedule"))) {
            m.setAccessible(true);
            handleMethod = m;
            break;
          }
        }
      }
      if (handleMethod == null) {
        throw new NoSuchMethodException("Could not find scheduling handle on " + regionDataClass.getName() + ". Methods: " + dumpMethods(regionDataClass));
      }
      this.getSchedulingHandle = handleMethod;

      final Class<?> handleClass = handleMethod.getReturnType();
      this.tickReports = new Method[TICK_REPORT_METHODS.length];
      for (int i = 0; i < TICK_REPORT_METHODS.length; i++) {
        final Method report = handleClass.getMethod(TICK_REPORT_METHODS[i], long.class);
        report.setAccessible(true);
        this.tickReports[i] = report;
      }

      final Class<?> reportClass = this.tickReports[0].getReturnType();
      this.tpsData = reportClass.getMethod("tpsData");
      this.timePerTickData = reportClass.getMethod("timePerTickData");
      this.tpsData.setAccessible(true);
      this.timePerTickData.setAccessible(true);

      final Class<?> segmentedAverageClass = this.tpsData.getReturnType();
      this.segmentAll = segmentedAverageClass.getMethod("segmentAll");
      this.segmentAll.setAccessible(true);

      final Class<?> segmentDataClass = this.segmentAll.getReturnType();
      this.segmentAverage = segmentDataClass.getMethod("average");
      this.segmentAverage.setAccessible(true);

      LOGGER.info(
        "Folia path: CraftWorld.getHandle() -> {}.{} -> {} -> getData() -> {}.{} -> {}.{} -> {}().segmentAll().average()",
        serverLevelClass.getSimpleName(), regField.getName(),
        this.computeForAllRegions.getName(),
        regionDataClass.getSimpleName(), handleMethod.getName(),
        handleClass.getSimpleName(), TICK_REPORT_METHODS[WINDOW_15S],
        this.tpsData.getName()
      );
    }

    double @Nullable [] regionTickData(final Player player, final int window) {
      try {
        final Object handle = this.playerRegionHandle(player);
        if (handle == null) {
          return null;
        }
        final Object report = this.tickReports[window].invoke(handle, System.nanoTime());
        if (report == null) {
          return null;
        }
        return new double[]{
          this.average(this.tpsData, report),
          this.average(this.timePerTickData, report) / 1.0E6D
        };
      } catch (final Throwable t) {
        return null;
      }
    }

    double @Nullable [] regionTps(final Player player) {
      try {
        final Object handle = this.playerRegionHandle(player);
        if (handle == null) {
          return null;
        }
        final long now = System.nanoTime();
        final double[] windows = new double[TICK_REPORT_METHODS.length];
        for (int i = 0; i < windows.length; i++) {
          final Object report = this.tickReports[i].invoke(handle, now);
          if (report == null) {
            return null;
          }
          windows[i] = this.average(this.tpsData, report);
        }
        return windows;
      } catch (final Throwable t) {
        return null;
      }
    }

    private @Nullable Object playerRegionHandle(final Player player) throws ReflectiveOperationException {
      final Location location = player.getLocation();
      final Object serverLevel = this.getCraftWorldHandle.invoke(player.getWorld());
      final Object regionizer = this.regioniserField.get(serverLevel);
      if (regionizer == null) {
        return null;
      }
      final Object region = this.getRegionAt.invoke(regionizer, location.getBlockX() >> 4, location.getBlockZ() >> 4);
      return region == null ? null : this.handle(region);
    }

    Snapshot takeSnapshot() {
      final List<Object> regions = new ArrayList<>();
      try {
        final Consumer<Object> collector = regions::add;
        for (final World world : Bukkit.getWorlds()) {
          final Object regionizer = this.regioniserField.get(this.getCraftWorldHandle.invoke(world));
          if (regionizer == null) {
            continue;
          }
          this.computeForAllRegions.invoke(regionizer, collector);
        }
      } catch (final Throwable t) {
        LOGGER.debug("Failed to enumerate Folia regions", t);
      }

      final long now = System.nanoTime();
      final List<double[]> regionTps = new ArrayList<>(regions.size());
      final List<Double> regionMspt = new ArrayList<>(regions.size());
      for (final Object region : regions) {
        final double[] windows = new double[TICK_REPORT_METHODS.length];
        Arrays.fill(windows, IDLE_TPS);
        double mspt = -1;
        try {
          final Object handle = this.handle(region);
          if (handle != null) {
            for (int i = 0; i < windows.length; i++) {
              final Object report = this.tickReports[i].invoke(handle, now);
              if (report == null) {
                continue;
              }
              windows[i] = this.average(this.tpsData, report);
              if (i == WINDOW_15S) {
                mspt = this.average(this.timePerTickData, report) / 1.0E6D;
              }
            }
          }
        } catch (final Throwable t) {
          LOGGER.debug("Failed to read Folia region tick report", t);
        }
        regionTps.add(windows);
        if (mspt >= 0) {
          regionMspt.add(mspt);
        }
      }
      return new Snapshot(now, regionTps, regionMspt);
    }

    private @Nullable Object handle(final Object region) throws ReflectiveOperationException {
      final Object data = this.getData.invoke(region);
      return data == null ? null : this.getSchedulingHandle.invoke(data);
    }

    private double average(final Method dataAccessor, final Object report) throws ReflectiveOperationException {
      final Object segmented = dataAccessor.invoke(report);
      if (segmented == null) {
        return 0;
      }
      final Object segment = this.segmentAll.invoke(segmented);
      if (segment == null) {
        return 0;
      }
      return (Double) this.segmentAverage.invoke(segment);
    }

    private static @Nullable Method firstMethod(final Class<?> owner, final int parameterCount, final String... names) {
      for (final String name : names) {
        for (final Method m : owner.getDeclaredMethods()) {
          if (m.getName().equals(name) && m.getParameterCount() == parameterCount) {
            m.setAccessible(true);
            return m;
          }
        }
      }
      return null;
    }

    private static String dumpMethods(final Class<?> clazz) {
      final StringBuilder sb = new StringBuilder("[");
      for (final Method m : clazz.getDeclaredMethods()) {
        sb.append(m.getReturnType().getSimpleName()).append(" ").append(m.getName()).append("(").append(m.getParameterCount()).append("), ");
      }
      sb.append("]");
      return sb.toString();
    }

    private static String dumpFields(final Class<?> clazz) {
      final StringBuilder sb = new StringBuilder("[");
      for (final Field f : clazz.getDeclaredFields()) {
        sb.append(f.getType().getSimpleName()).append(" ").append(f.getName()).append(", ");
      }
      sb.append("]");
      return sb.toString();
    }
  }
}
