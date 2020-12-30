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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.geotools.data.ows.Layer;
import org.geotools.data.ows.WMSCapabilities;
import org.geotools.data.wms.WMSUtils;
import org.geotools.data.wms.WebMapServer;
import org.geotools.data.wms.request.GetMapRequest;
import org.geotools.data.wms.response.GetMapResponse;

public class WtsImage {
  public static final int TILE_SIZE = 256;
  public static final double WY0 = 45;
  public static final double WX0 = -22.5;
  public static final Map<String, BufferedImage> cache = new HashMap<String, BufferedImage>();

  public static void render(Graphics2D g2, Dimension size, double scaleFactor, int x, int y) {
    try {
      int logIntScale = (int) (Math.log(scaleFactor) / Math.log(2));
      double wtsScale = Math.pow(2, logIntScale);
      double relScale = wtsScale / scaleFactor;
      System.out.println(scaleFactor + ":" + wtsScale + ":" + relScale);
      URL url = new URL("http://localhost:8081/geoserver/postgis/wms?VERSION=1.3.1");

      WebMapServer wms = new WebMapServer(url);
      WMSCapabilities capabilities = wms.getCapabilities();
      Layer[] layers = WMSUtils.getNamedLayers(capabilities);
      ExecutorService es = Executors.newCachedThreadPool();
      for (int i = (int) (-x / TILE_SIZE) - 1;
          i < (size.width - x) / TILE_SIZE * relScale + 1;
          i++) {
        for (int j = (int) (-y / TILE_SIZE) - 1;
            j < (size.height - y) / TILE_SIZE * relScale + 1;
            j++) {
          final int ii = i;
          final int jj = j;
          es.execute(
              () -> {
                try {
                  BufferedImage img = cache.get(logIntScale + ":" + ii + ":" + jj);
                  if (img == null) {
                    GetMapRequest request = wms.createGetMapRequest();
                    request.setFormat("image/png");
                    request.setDimensions(TILE_SIZE, TILE_SIZE);
                    request.setTransparent(true);
                    request.setSRS("EPSG:4326");
                    System.out.println(
                        (WY0 - jj / wtsScale)
                            + ","
                            + (WX0 + ii / wtsScale)
                            + ","
                            + (WY0 - (jj - 1) / wtsScale)
                            + ","
                            + (WX0 + (ii + 1) / wtsScale));
                    request.setBBox(
                        (WY0 - jj / wtsScale)
                            + ","
                            + (WX0 + ii / wtsScale)
                            + ","
                            + (WY0 - (jj - 1) / wtsScale)
                            + ","
                            + (WX0 + (ii + 1) / wtsScale));
                    for (Layer layer : layers) {
                      if (layer.getName().equals("regional")) request.addLayer(layer);
                    }
                    GetMapResponse response = (GetMapResponse) wms.issueRequest(request);
                    img = ImageIO.read(response.getInputStream());
                    cache.put(logIntScale + ":" + ii + ":" + jj, img);
                  }
                  g2.drawImage(
                      img,
                      x + (int) (ii * TILE_SIZE / relScale),
                      y + (int) (jj * TILE_SIZE / relScale),
                      (int) (TILE_SIZE / relScale),
                      (int) (TILE_SIZE / relScale),
                      null);
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
