/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <http://www.gnu.org/licenses/> and specifically the Affero license
 * text at <http://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.lib.image;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import net.rptools.maptool.model.Zone;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.ows.wms.Layer;
import org.geotools.ows.wms.WMSCapabilities;
import org.geotools.ows.wms.WMSUtils;
import org.geotools.ows.wms.WebMapServer;
import org.geotools.ows.wms.request.GetMapRequest;
import org.geotools.ows.wms.response.GetMapResponse;

public class WmsImage {
  private static final Logger log = LogManager.getLogger(WmsImage.class);
  /**
   * Calculating world coordinates is a complex business and certainly not a linear transformation
   * at all. We still assume so, for the simple reason, that I assume that 10%-20% inaccuracy in the
   * absolute numbers is not going to matter for RPGs. If it ever does, this calculation must be
   * redone, or people providing maps with geo-coordinates need to drop maps in a place where the
   * numbers are correct. Thus: 1 degree (LAT or LON) = 100km. The second assumption is 50px per
   * cell and 2m per cell, i.e. 25p/m.
   */
  public static final int WMS_SCALE = 25 * 100 * 1000;
  // Tile size with the WMS server
  private static final int TILE = 256;
  // Named layers, TOOD: should be from map configuration.
  // Example wmsLayers = "TOPO-WMS";
  // WMS URL , TODO: should be from map configuration.
  // Example wmsURL = "https://ows.mundialis.de/services/service?VERSION=1.3.1&Service=WMS";
  // Weak Caching.
  private static final CircularFifoQueue<String> cacheKeys = new CircularFifoQueue<String>(200);
  private static final Map<String, BufferedImage> cache = new WeakHashMap<String, BufferedImage>();

  /**
   * Render method. Takes view (= screen = graphics) coordinates and maps them into WMS coordinates.
   * It uses base-2 scaling for communication with the WMS server. It also tiles and keep the tiles
   * cached.
   *
   * @param zoneId id of zone to keep the cache organized.
   * @param g2 graphics context to draw on
   * @param size graphics context size
   * @param scale scale between screen and user space
   * @param offx x offset between screen and user space
   * @param offy y offset between screen and user space
   */
  public static void render(
      Zone zone, Graphics2D g2, Dimension size, double scale, int offx, int offy) {
    try {
      String wmsURL = zone.getWmsUrl();
      log.info("wmsUrl={}", wmsURL);
      if (wmsURL == null || wmsURL.length() < 13) {
        return;
      }
      int logScale = (int) Math.floor(Math.log(scale) / Math.log(2));
      double scaleInt = Math.pow(2, logScale);
      double scaleFrac = scale / scaleInt;
      log.info("scale={}, scaleInt={}, scaleFrac={}", scale, scaleInt, scaleFrac);
      // User space suffix u
      double x1u = (-offx) / scaleFrac;
      double y1u = (-offy) / scaleFrac;
      double x2u = (size.width - offx) / scaleFrac;
      double y2u = (size.height - offy) / scaleFrac;
      // Tile vertices suffix t
      double x1t = Math.floor(x1u / TILE);
      double y1t = Math.floor(y1u / TILE);
      double x2t = Math.floor(x2u / TILE);
      double y2t = Math.floor(y2u / TILE);
      log.debug(
          "x1u={}, y1u={}, x2u={}, y2u={}, x1t={}, y1t={}, x2t={}, y2t={}",
          x1u,
          y1u,
          x2u,
          y2u,
          x1t,
          y1t,
          x2t,
          y2t);

      WebMapServer wms = new WebMapServer(new URL(wmsURL));
      WMSCapabilities capabilities = wms.getCapabilities();
      Layer[] foundLayers = WMSUtils.getNamedLayers(capabilities);
      List<String> wmsLayers = zone.getWmsLayers();
      List<Layer> wantLayers =
          List.of(foundLayers).stream()
              .filter(it -> wmsLayers.contains(it.getName()))
              .collect(Collectors.toList());
      ExecutorService executorService = Executors.newCachedThreadPool();
      for (int x = (int) x1t; x < x2t + 1; x++) {
        final int xt = x;
        for (int y = (int) y1t; y < y2t + 1; y++) {
          final int yt = y;
          executorService.execute(
              () -> {
                try {
                  String key = zone.getId() + " " + scaleInt + " " + xt + " " + yt;
                  BufferedImage img = cache.get(key);
                  if (img == null) {
                    // WMS coordinates suffix w
                    double x1w = xt / scaleInt / zone.getWmsScale();
                    double y1w = -yt / scaleInt / zone.getWmsScale();
                    double x2w = (xt + 1) / scaleInt / zone.getWmsScale();
                    double y2w = -(yt + 1) / scaleInt / zone.getWmsScale();

                    GetMapRequest request = wms.createGetMapRequest();
                    request.setFormat("image/png");
                    request.setDimensions(2 * TILE, 2 * TILE);
                    request.setTransparent(true);
                    request.setSRS("EPSG:4326");
                    request.setBBox(y2w + "," + x1w + "," + y1w + "," + x2w);
                    for (Layer layer : wantLayers) {
                      request.addLayer(layer);
                    }
                    GetMapResponse response = (GetMapResponse) wms.issueRequest(request);
                    img = ImageIO.read(response.getInputStream());
                    cacheKeys.add(key);
                    cache.put(key, img);
                  }
                  // Screen view suffix s
                  int x1s = (int) (xt * TILE * scaleFrac + offx);
                  int y1s = (int) (yt * TILE * scaleFrac + offy);
                  int x2s = (int) ((xt * TILE + TILE) * scaleFrac + offx);
                  int y2s = (int) ((yt * TILE + TILE) * scaleFrac + offy);

                  log.debug("key={}, x1s={}, y1s={}, x2s={}, y2s={}", key, x1s, y1s, x2s, y2s);
                  g2.drawImage(img, x1s, y1s, x2s - x1s, y2s - y1s, null);
                } catch (Exception e) {
                  System.err.println(e);
                }
              });
        }
      }
      executorService.shutdown();
      executorService.awaitTermination(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      System.err.println(e);
    }
  }
}
