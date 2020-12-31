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
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.geotools.ows.wms.Layer;
import org.geotools.ows.wms.WMSCapabilities;
import org.geotools.ows.wms.WMSUtils;
import org.geotools.ows.wms.WebMapServer;
import org.geotools.ows.wms.request.GetMapRequest;
import org.geotools.ows.wms.response.GetMapResponse;

public class WtsImage {
  public static final int TILE = 256;
  public static final double WY0 = 45;
  public static final double WX0 = 22.5;
  public static final double WMS_SCALE = .5;
  // Weak Caching
  public static final CircularFifoQueue<String> cacheKeys = new CircularFifoQueue<String>(200);
  public static final Map<String, BufferedImage> cache = new WeakHashMap<String, BufferedImage>();

  public static void render(
      Graphics2D g2, Dimension size_s, double scale_u2s, int offx_us, int offy_us) {
    try {
      int logScale_u2s = (int) Math.floor(Math.log(scale_u2s) / Math.log(2));
      double scaleInt_u2s = Math.pow(2, logScale_u2s);
      double scaleFrac_u2s = scale_u2s / scaleInt_u2s;
      System.out.println(scale_u2s + ":" + scaleInt_u2s + ":" + scaleFrac_u2s);
      // Screen view subscript s
      double x1_s = 0;
      double y1_s = 0;
      double x2_s = size_s.width;
      double y2_s = size_s.height;
      // User space subscript u
      double x1_u = (x1_s - offx_us) / scale_u2s;
      double y1_u = (y1_s - offy_us) / scale_u2s;
      double x2_u = (x2_s - offx_us) / scale_u2s;
      double y2_u = (y2_s - offy_us) / scale_u2s;
      // Map of world subscript m
      double x1_m = WMS_SCALE * x1_u * scaleInt_u2s;
      double y1_m = WMS_SCALE * y1_u * scaleInt_u2s;
      double x2_m = WMS_SCALE * x2_u * scaleInt_u2s;
      double y2_m = WMS_SCALE * y2_u * scaleInt_u2s;
      // Tile vertices subscript t
      double x1_t = Math.floor(x1_m / TILE);
      double y1_t = Math.floor(y1_m / TILE);
      double x2_t = Math.floor(x2_m / TILE);
      double y2_t = Math.floor(y2_m / TILE);

      URL url =
          new URL(
              "https://ows.mundialis.de/services/service?VERSION=1.3.1&Request=GetCapabilities&Service=WMS");
      WebMapServer wms = new WebMapServer(url);
      WMSCapabilities capabilities = wms.getCapabilities();
      Layer[] layers = WMSUtils.getNamedLayers(capabilities);
      ExecutorService es = Executors.newCachedThreadPool();
      for (int i_t = (int) x1_t; i_t < x2_t + 1; i_t++) {
        final int ii_t = i_t;
        for (int j_t = (int) y1_t; j_t < y2_t + 1; j_t++) {
          final int jj_t = j_t;
          es.execute(
              () -> {
                try {
                  String key = scaleInt_u2s + ":" + ii_t + " " + jj_t;
                  BufferedImage img = cache.get(key);
                  if (img == null) {
                    // WMS coordinates subscript w
                    double x1_w = WX0 + ii_t / scaleInt_u2s;
                    double y1_w = WY0 - jj_t / scaleInt_u2s;
                    double x2_w = WX0 + (ii_t + 1) / scaleInt_u2s;
                    double y2_w = WY0 - (jj_t + 1) / scaleInt_u2s;

                    GetMapRequest request = wms.createGetMapRequest();
                    request.setFormat("image/png");
                    request.setDimensions(2 * TILE, 2 * TILE);
                    request.setTransparent(true);
                    request.setSRS("EPSG:4326");
                    request.setBBox(y2_w + "," + x1_w + "," + y1_w + "," + x2_w);
                    for (Layer layer : layers) {
                      if (layer.getName().equals("TOPO-WMS")) request.addLayer(layer);
                    }
                    GetMapResponse response = (GetMapResponse) wms.issueRequest(request);
                    img = ImageIO.read(response.getInputStream());
                    cacheKeys.add(key);
                    cache.put(key, img);
                  }
                  // Map of world subscript m
                  int ix1_m = ii_t * TILE;
                  int iy1_m = jj_t * TILE;
                  int ix2_m = (ii_t + 1) * TILE;
                  int iy2_m = (jj_t + 1) * TILE;
                  // User space subscript u
                  double ix1_u = ix1_m / WMS_SCALE / scaleInt_u2s;
                  double iy1_u = iy1_m / WMS_SCALE / scaleInt_u2s;
                  double ix2_u = ix2_m / WMS_SCALE / scaleInt_u2s;
                  double iy2_u = iy2_m / WMS_SCALE / scaleInt_u2s;
                  // Screen view subscript s
                  int ix1_s = (int) (ix1_u * scale_u2s + offx_us);
                  int iy1_s = (int) (iy1_u * scale_u2s + offy_us);
                  int ix2_s = (int) (ix2_u * scale_u2s + offx_us);
                  int iy2_s = (int) (iy2_u * scale_u2s + offy_us);

                  g2.drawImage(img, ix1_s, iy1_s, ix2_s - ix1_s, iy2_s - iy1_s, null);
                } catch (Exception e) {
                  System.err.println(e);
                }
              });
        }
      }
      es.shutdown();
      es.awaitTermination(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      System.err.println(e);
    }
  }
}
