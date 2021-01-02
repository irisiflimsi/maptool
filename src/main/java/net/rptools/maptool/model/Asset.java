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
package net.rptools.maptool.model;

import com.google.gson.JsonObject;
import com.thoughtworks.xstream.annotations.XStreamConverter;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import net.rptools.lib.MD5Key;
import net.rptools.lib.image.ImageUtil;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.util.ImageManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/** The binary representation of an image. */
public class Asset {
  public static final String DATA_EXTENSION = "data";
  private static final Logger log = LogManager.getLogger(Asset.class);

  private MD5Key id;
  private String name;
  private String extension;
  private String type = "image";

  @XStreamConverter(AssetImageConverter.class)
  private byte[] image;

  /**
   * Optional. Shorthand for the coordinates in a TIFF. [0] raster x image coord of pivot [1] raster
   * y image coord of pivot [2] geo pt x (decimal) degrees of pivot [3] geo pt y (decimal) degrees
   * of pivot [4] scale x (decimal) degrees to a pixel [5] scale y (decimal) degrees to a pixel [6]
   * number of params parsed (to signal parsing completed)
   */
  private double[] geoopts;

  protected Asset() {}

  public Asset(String name, byte[] image) {
    this.image = image;
    this.name = name;
    if (image != null) {
      this.id = new MD5Key(image);
      extension = null;
      getImageExtension();
    }
  }

  public Asset(String name, BufferedImage image) {
    try {
      this.image = ImageUtil.imageToBytes(image);
    } catch (IOException e) {
      e.printStackTrace();
    }
    this.name = name;
    if (this.image != null) {
      this.id = new MD5Key(this.image);
      extension = null;
      getImageExtension();
    }
  }

  public Asset(MD5Key id) {
    this.id = id;
  }

  public MD5Key getId() {
    return id;
  }

  public void setId(MD5Key id) {
    this.id = id;
  }

  public byte[] getImage() {
    return image;
  }

  public void setImage(byte[] image) {
    this.image = image;
    extension = null;
    getImageExtension();
  }

  public double[] getGeopts() {
    if (geoopts == null) return null;
    return Arrays.copyOf(geoopts, geoopts.length);
  }

  public String getImageExtension() {
    if (extension == null) {
      extension = "";
      try {
        if (image != null && image.length >= 4) {
          InputStream is = new ByteArrayInputStream(image);
          ImageInputStream iis = ImageIO.createImageInputStream(is);
          Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
          if (readers.hasNext()) {
            ImageReader reader = readers.next();
            reader.setInput(iis);
            extension = reader.getFormatName().toLowerCase();
            geoopts = null;
            IIOMetadata metadata = reader.getImageMetadata(0);
            if (metadata != null) {
              String[] names = metadata.getMetadataFormatNames();
              if (names != null) {
                for (int i = 0; geoopts == null && i < names.length; i++) {
                  double[] pts = getGeoTiffMetadata(metadata.getAsTree(names[i]), null);
                  if (pts[6] > 5.9) {
                    geoopts = pts;
                    log.debug("geoopts={}", geoopts);
                  }
                }
              }
            }
          }
          // We can store more than images, eg HeroLabData in the form of a HashMap, assume this if
          // an image type can not be established
          if (extension.isEmpty()) extension = DATA_EXTENSION;
        }
      } catch (IOException e) {
        MapTool.showError("IOException?!", e); // Can this happen??
      }
    }
    return extension;
  }

  public String getName() {
    return name;
  }

  /**
   * Get the properties of the asset and put them in a JsonObject.
   *
   * @return the JsonObject with the properties.
   */
  public JsonObject getProperties() {
    JsonObject properties = new JsonObject();
    properties.addProperty("type", type);
    properties.addProperty("subtype", extension);
    properties.addProperty("id", id.toString());
    properties.addProperty("name", name);

    Image img = ImageManager.getImageAndWait(id); // wait until loaded, so width/height are correct
    String status = "loaded";
    if (img == ImageManager.BROKEN_IMAGE) {
      status = "broken";
    } else if (img == ImageManager.TRANSFERING_IMAGE) {
      status = "transferring";
    }
    properties.addProperty("status", status);
    properties.addProperty("width", img.getWidth(null));
    properties.addProperty("height", img.getHeight(null));
    return properties;
  }

  public boolean isTransfering() {
    return AssetManager.isAssetRequested(id);
  }

  public String getType() {
    return type;
  }

  @Override
  public String toString() {
    return id + "/" + name + "(" + (image != null ? image.length : "-") + ")";
  }

  @Override
  public int hashCode() {
    return getId().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Asset)) {
      return false;
    }
    Asset asset = (Asset) obj;
    return asset.getId().equals(getId());
  }

  /**
   * Get GeoTIFF metadata. We only support a very small subset of the potential information a
   * GeoTIFF can carry. E.g. non-scaling transformation such as rotations are not supported.
   *
   * @param node GeoTIFF uses XML metadata. This is the root of that tree.
   * @param result values already parsed according to the definition in attribute geoopts
   * @return values now parsed according to the definition in attribute geoopts
   */
  private double[] getGeoTiffMetadata(Node node, double[] result) {
    if (result == null) result = new double[7];
    try {
      NamedNodeMap map = node.getAttributes();
      if (map != null) {
        int length = map.getLength();
        for (int i = 0; i < length; i++) {
          Node attr = map.item(i);
          if (attr.getNodeName().equals("number") && attr.getNodeValue().equals("33922")) {
            result[0] = extractValueFromChild(node, 0);
            result[1] = extractValueFromChild(node, 1);
            result[2] = extractValueFromChild(node, 3);
            result[3] = extractValueFromChild(node, 4);
            result[6] += 4;
          }
          if (attr.getNodeName().equals("number") && attr.getNodeValue().equals("33550")) {
            result[4] = extractValueFromChild(node, 0);
            result[5] = extractValueFromChild(node, 1);
            result[6] += 2;
          }
          // Return when done
          if (result[6] > 5.9) {
            return result;
          }
        }
      }

      // Continue parsing down the tree.
      Node child = node.getFirstChild();
      while (child != null) {
        result = getGeoTiffMetadata(child, result);
        if (result[6] > 5.9) {
          return result;
        }
        child = child.getNextSibling();
      }
    } catch (Exception e) {
      // Wrongly encoded GeoTIFF or using features unsupported by us.
      // Ignore and continue.
      e.printStackTrace();
    }
    return result;
  }

  /**
   * Private static node extractor. Get value attribute from childIdx, a child from the first child
   * of parent - as double.
   */
  private static double extractValueFromChild(Node parent, int childIdx) {
    return Double.parseDouble(
        parent
            .getFirstChild()
            .getChildNodes()
            .item(childIdx)
            .getAttributes()
            .getNamedItem("value")
            .getNodeValue());
  }
}
