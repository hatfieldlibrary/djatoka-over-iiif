package edu.virginia.lib.iiif.resources;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.stream.JsonParsingException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;

/**
 * A service to help masquerade an IIIF service as a Djatoka image service.
 * 
 * This was written as a temporary measure in the migration from Djatoka to
 * an IIIF-compliant server.  In order to easily swap-in that image server, 
 * we wrote this shim so that the disseminators on fedora 3 that *would* proxy
 * content from Djatoka instead proxy content from our new image server.  This 
 * allows all layers of the stack above fedora to be unmodified, but still get
 * the performance and reliability benefits of having a non-djatoka image server
 * on the back-end.
 */
@Path("/")
public class DjatokaImage {
    
    private static final String IIIF_SERVER_ROOT = "http://iiif.lib.virginia.edu/iiif/";
    
    private HttpClient client;
    
    public DjatokaImage() {
        client = HttpClients.createDefault();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/djatoka-metadata.json")
    public JsonObject getMetadata(@QueryParam("url") final String url) throws ClientProtocolException, IOException {
        return getDjatokaMetadata(getIIIFMetadata(getPidFromURL(url)));
    }
    
    JsonObject getDjatokaMetadata(JsonObject iiifMetadata) throws UnsupportedEncodingException {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add("identifier", iiifMetadata.getString("@id"));
        builder.add("imagefile", "/this/is/a/fake/path/to/spoof/djatoka/" + URLEncoder.encode(iiifMetadata.getString("@id"), "UTF-8"));
        builder.add("width", String.valueOf(iiifMetadata.getInt("width")));
        builder.add("height", String.valueOf(iiifMetadata.getInt("height")));
        final int levelCount = iiifMetadata.getJsonArray("tiles").getJsonObject(0).getJsonArray("scaleFactors").size() - 1;
        builder.add("dwtLevels", String.valueOf(levelCount));
        builder.add("levels", String.valueOf(levelCount));
        builder.add("compositingLayerCount", "1");
        return builder.build();
    }
    
    /**
     * Compensates for the API differences between Djatoka and IIIF for region requests.
     * Djatoka expects a "level" to indicate scale and the region specification to be
     * y,x,h,w for that level.  IIIF expects the region information to be based on the full
     * image size, then a scale factor to indicate how large the resulting image should be.
     * @throws URISyntaxException 
     */
    @GET
    @Path("/getRegionFromIIIF")
    public Response redirectGetRegion(@QueryParam("contentUrl") final String contentUrl, @QueryParam("level") String level, @QueryParam("region") final String region, @QueryParam("scale") String scaleParam) throws ClientProtocolException, IOException, URISyntaxException {
        final String pid = getPidFromURL(contentUrl);

        // Support for just a scaled full image
        if ((region == null || "".equals(region)) && (scaleParam != null && !"".equals(scaleParam))) {
            Pattern percent = Pattern.compile("(\\d*\\.\\d+)");
            Matcher m = percent.matcher(scaleParam);
            if (m.matches()) {
                final String url = IIIF_SERVER_ROOT + pid + "/full/pct:" + (100f * Float.parseFloat(m.group(1))) + "," + (100f * Float.parseFloat(m.group(1))) + "/0/default.jpg";
                return Response.temporaryRedirect(new URI(url)).build();
                
            } else {
                final String url = IIIF_SERVER_ROOT + pid + "/full/!" + (scaleParam.indexOf(',') != -1 ? scaleParam : scaleParam + "," + scaleParam) + "/0/default.jpg";
                return Response.temporaryRedirect(new URI(url)).build();
            }
        }
        
        JsonObject iiifMetadata = getIIIFMetadata(pid);
        final JsonArray scaleFactor = iiifMetadata.getJsonArray("tiles").getJsonObject(0).getJsonArray("scaleFactors");
        if (level == null || "-1".equals(level)) {
            level = String.valueOf(scaleFactor.size() -1);
        }
        int scale = scaleFactor.getInt(scaleFactor.size() - (Integer.valueOf(level) +1));
        
        String iiifRegion = "full";
        if (region != null && region.indexOf(',') != -1) {
            final String[] yxhw = region.split(",");
            iiifRegion = yxhw[1] + "," + yxhw[0] + "," + scale(yxhw[3], scale) + "," + scale(yxhw[2], scale);
        }
        
        final String url = IIIF_SERVER_ROOT + pid + "/" + iiifRegion + "/pct:" + (100f / (float) scale) + "/0/default.jpg";
        return Response.temporaryRedirect(new URI(url)).build();
    }
    
    /**
     * Multiplies the Int value of the provided string by the int value of the factor.
     */
    private int scale(String relativeToScale, int factor) {
        return Integer.valueOf(relativeToScale) * factor;
    }
    
    /**
     * Make an HTTP request for the IIIF metadata for the image at a given pid and returns
     * the parsed Json response.
     */
    private JsonObject getIIIFMetadata(final String pid) throws ClientProtocolException, IOException {
        final String url = getMetadataUrlForPid(pid);
        HttpGet get = new HttpGet(url);
        try {
            HttpResponse r = client.execute(get);
            final JsonReader reader = Json.createReaderFactory(null).createReader(r.getEntity().getContent());
            return reader.readObject();
        } catch (JsonParsingException ex) {
           throw new RuntimeException("Unable to parse response from \"" + url + "\"!", ex);
        } finally {
            get.releaseConnection();
        }
    }
    
    /**
     * Parses the PID from a fedora url.
     */
    private String getPidFromURL(final String url) {
        final Matcher m = Pattern.compile("^.*((objects)|(get))/([^/]*)/.*$").matcher(url);
        if (!m.matches()) {
            throw new RuntimeException("Unable to determine pid from \"" + url + "\"!");
        }
        return m.group(4);
    }
    
    /**
     * Gets the URL for IIIF metadata for the image associated with the 
     * given pid.
     */
    private String getMetadataUrlForPid(final String pid) {
        return IIIF_SERVER_ROOT + pid + "/info.json";
    }
    
}
