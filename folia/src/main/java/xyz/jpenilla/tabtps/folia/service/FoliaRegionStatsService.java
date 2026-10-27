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

import java.util.List;
import xyz.jpenilla.tabtps.common.service.RegionStatsService;

public final class FoliaRegionStatsService implements RegionStatsService {
  private final FoliaTickTimeService tickTimeService;

  public FoliaRegionStatsService(final FoliaTickTimeService tickTimeService) {
    this.tickTimeService = tickTimeService;
  }

  private List<Double> collectRegionTps() {
    return this.tickTimeService.sortedRegionTps();
  }

  @Override
  public double lowestRegionTps() {
    final List<Double> tps = this.collectRegionTps();
    if (tps.isEmpty()) return 20.0;
    return tps.get(0);
  }

  @Override
  public double medianRegionTps() {
    final List<Double> tps = this.collectRegionTps();
    if (tps.isEmpty()) return 20.0;
    final int size = tps.size();
    if (size % 2 == 0) {
      return (tps.get(size / 2 - 1) + tps.get(size / 2)) / 2.0;
    }
    return tps.get(size / 2);
  }

  @Override
  public double highestRegionTps() {
    final List<Double> tps = this.collectRegionTps();
    if (tps.isEmpty()) return 20.0;
    return tps.get(tps.size() - 1);
  }
}
