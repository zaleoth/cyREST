package org.cytoscape.rest.internal.resource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.inject.Singleton;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

import org.cytoscape.io.write.CyWriter;
import org.cytoscape.model.CyEdge;
import org.cytoscape.model.CyIdentifiable;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.model.CyNode;
import org.cytoscape.rest.internal.datamapper.VisualStyleMapper;
import org.cytoscape.rest.internal.serializer.VisualStyleSerializer;
import org.cytoscape.view.model.CyNetworkView;
import org.cytoscape.view.model.View;
import org.cytoscape.view.model.VisualLexicon;
import org.cytoscape.view.model.VisualProperty;
import org.cytoscape.view.presentation.RenderingEngine;
import org.cytoscape.view.presentation.RenderingEngineManager;
import org.cytoscape.view.presentation.property.BasicVisualLexicon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST API for Network View objects.
 * 
 * TODO: add custom view information section
 * 
 */
@Singleton
@Path("/v1/networks/{networkId}/views")
public class NetworkViewResource extends AbstractResource {

	// Filter rendering engine by ID.
	private static final String DING_ID = "org.cytoscape.ding";

	private static final String DEF_HEIGHT = "600";

	@Context
	@NotNull
	private RenderingEngineManager renderingEngineManager;
	
	private final VisualStyleMapper styleMapper;
	private final VisualStyleSerializer styleSerializer;

	private VisualLexicon lexicon;
	private Collection<VisualProperty<?>> nodeLexicon;
	private Collection<VisualProperty<?>> edgeLexicon;
	private Collection<VisualProperty<?>> networkLexicon;

	
	
	public NetworkViewResource() {
		super();
		this.styleMapper = new VisualStyleMapper();
		this.styleSerializer = new VisualStyleSerializer();
	}

	private final void initLexicon() {
		// Prepare lexicon
		this.lexicon = getLexicon();
		nodeLexicon = lexicon.getAllDescendants(BasicVisualLexicon.NODE);
		edgeLexicon = lexicon.getAllDescendants(BasicVisualLexicon.EDGE);
		networkLexicon = lexicon.getAllDescendants(BasicVisualLexicon.NETWORK);
		
	}
	/**
	 * @summary Create view for the network
	 * 
	 * @param networkId
	 *            Network SUID
	 * 
	 * @return SUID for the new Network View.
	 * 
	 */
	@POST
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public String createNetworkView(@PathParam("networkId") Long networkId) {
		final CyNetwork network = getCyNetwork(networkId);
		final CyNetworkView view = networkViewFactory.createNetworkView(network);
		networkViewManager.addNetworkView(view);
		return getNumberObjectString("networkViewSUID", view.getSUID());
	}

	/**
	 * Cytoscape can have multiple views per network model. This feature is not
	 * exposed to end-users, but you can access it from this API.
	 * 
	 * @summary Get number of views for the given network model
	 * 
	 * @param networkId
	 *            Network SUID
	 * 
	 * @return Number of views for the network model
	 * 
	 */
	@GET
	@Path("/count")
	@Produces(MediaType.APPLICATION_JSON)
	public String getNetworkViewCount(@PathParam("networkId") Long networkId) {
		return getNumberObjectString(JsonTags.COUNT, networkViewManager.getNetworkViews(getCyNetwork(networkId)).size());
	}

	/**
	 * 
	 * In general, a network has one view. But if there are multiple views, this
	 * deletes all of them.
	 * 
	 * @summary Delete all network views
	 * 
	 * @param networkId
	 *            Network SUID
	 * 
	 */
	@DELETE
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public void deleteAllNetworkViews(@PathParam("networkId") Long networkId) {
		try {
			final Collection<CyNetworkView> views = this.networkViewManager.getNetworkViews(getCyNetwork(networkId));
			final Set<CyNetworkView> toBeDestroyed = new HashSet<CyNetworkView>(views);
			for (final CyNetworkView view : toBeDestroyed) {
				networkViewManager.destroyNetworkView(view);
			}
		} catch (Exception e) {
			throw getError("Could not delete network views for network with SUID: " + networkId, e,
					Response.Status.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * 
	 * This returns a view for the network.
	 * 
	 * 
	 * @summary Convenience method to get the first view object.
	 * 
	 * @param networkId
	 *            Network SUID
	 * 
	 * @return
	 */
	@GET
	@Path("/first")
	@Produces(MediaType.APPLICATION_JSON)
	public String getFirstNetworkView(@PathParam("networkId") Long networkId) {
		final Collection<CyNetworkView> views = this.getCyNetworkViews(networkId);
		if (views.isEmpty()) {
			throw new NotFoundException("Could not find view for the network: " + networkId);
		}
		return getNetworkViewString(views.iterator().next());
	}

	/**
	 * @summary Delete a view whatever found first.
	 * 
	 * @param networkId
	 *            Network SUID
	 * 
	 */
	@DELETE
	@Path("/first")
	@Produces(MediaType.APPLICATION_JSON)
	public void deleteFirstNetworkView(@PathParam("networkId") Long networkId) {
		final Collection<CyNetworkView> views = this.getCyNetworkViews(networkId);
		if (views.isEmpty()) {
			return;
		}
		networkViewManager.destroyNetworkView(views.iterator().next());
	}

	/**
	 * To use this API, you need to know SUID of the CyNetworkView, in addition
	 * to CyNetwork SUID.
	 * 
	 * @summary Get a network view
	 * 
	 * @param networkId
	 *            Network SUID
	 * @param viewId
	 *            Network View SUID
	 * 
	 * @return View in Cytoscape.js JSON. Currently, view information is (x, y)
	 *         location only.
	 */
	@GET
	@Path("/{viewId}")
	@Produces(MediaType.APPLICATION_JSON)
	public String getNetworkView(@PathParam("networkId") Long networkId, @PathParam("viewId") Long viewId) {
		final Collection<CyNetworkView> views = this.getCyNetworkViews(networkId);
		for (final CyNetworkView view : views) {
			final Long vid = view.getSUID();
			if (vid.equals(viewId)) {
				return getNetworkViewString(view);
			}
		}

		return "{}";
	}

	/**
	 * @summary Get SUID of all network views
	 * 
	 * @param networkId
	 *            Network SUID
	 * 
	 * @return Array of all network view SUIDs
	 * 
	 */
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Collection<Long> getAllNetworkViews(@PathParam("networkId") Long networkId) {
		final Collection<CyNetworkView> views = this.getCyNetworkViews(networkId);

		final Collection<Long> suids = new HashSet<Long>();

		for (final CyNetworkView view : views) {
			final Long viewId = view.getSUID();
			suids.add(viewId);
		}
		return suids;
	}

	private final String getNetworkViewString(final CyNetworkView networkView) {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		CyWriter writer = cytoscapeJsWriterFactory.createWriter(stream, networkView);
		String jsonString = null;
		try {
			writer.run(null);
			jsonString = stream.toString("UTF-8");
			stream.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return jsonString;
	}

	/**
	 * Generate a PNG image as stream. Default size is 600 px.
	 * 
	 * @summary Get PNG image of a network view
	 * 
	 * @param networkId
	 *            Network SUID
	 * @param viewId
	 *            Network View SUID
	 * @param height
	 *            Optional height of the image. Width will be set automatically.
	 * 
	 * @return PNG image stream.
	 */
	@GET
	@Path("/first.png")
	@Produces("image/png")
	public Response getFirstImage(@PathParam("networkId") Long networkId,
			@DefaultValue(DEF_HEIGHT) @QueryParam("h") int height) {
		final Collection<CyNetworkView> views = this.getCyNetworkViews(networkId);
		if (views.isEmpty()) {
			throw new NotFoundException("Could not find view for the network: " + networkId);
		}

		final CyNetworkView view = views.iterator().next();
		return imageGenerator(view, height, height);
	}

	/**
	 * Generate a PNG network image as stream. Default size is 600 px.
	 * 
	 * @summary Get PNG image of a network view
	 * 
	 * @param networkId
	 *            Network SUID
	 * @param viewId
	 *            Network View SUID
	 * @param height
	 *            Optional height of the image. Width will be set automatically.
	 * 
	 * @return PNG image stream.
	 */
	@GET
	@Path("/{viewId}.png")
	@Produces("image/png")
	public Response getImage(@PathParam("networkId") Long networkId, @PathParam("viewId") Long viewId,
			@DefaultValue(DEF_HEIGHT) @QueryParam("h") int height) {
		final Collection<CyNetworkView> views = this.getCyNetworkViews(networkId);
		for (final CyNetworkView view : views) {
			final Long vid = view.getSUID();
			if (vid.equals(viewId)) {
				return imageGenerator(view, height, height);
			}
		}

		throw new NotFoundException("Could not find view for the network: " + networkId);
	}

	private final Response imageGenerator(final CyNetworkView view, int width, int height) {
		final Collection<RenderingEngine<?>> re = renderingEngineManager.getRenderingEngines(view);
		if (re.isEmpty()) {
			throw new IllegalArgumentException("No rendering engine.");
		}
		try {
			RenderingEngine<?> engine = null;

			for (RenderingEngine<?> r : re) {
				if (r.getRendererId().equals(DING_ID)) {
					engine = r;
					break;
				}
			}
			if (engine == null) {
				throw new IllegalArgumentException("Could not find Ding rendering eigine.");
			}

			// This is safe for Ding network view.
			final BufferedImage image = (BufferedImage) engine.createImage(width, height);
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(image, "png", baos);
			final byte[] imageData = baos.toByteArray();

			return Response.ok(new ByteArrayInputStream(imageData)).build();
		} catch (Exception e) {
			e.printStackTrace();
			throw getError("Could not create image.", e, Response.Status.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * By passing list of key-value pair for each Visual Property, update node view.
	 * 
	 * The body should have the following JSON:
	 * 
	 * <pre>
	 * [
	 * 		{
	 * 			"SUID": SUID of node,
	 * 			"view": [
	 * 				{
	 * 					"visualProperty": "Visual Property Name, like NODE_FILL_COLOR",
	 * 					"value": "Serialized form of value, like 'red.'"
	 * 				},
	 * 				...
	 * 				{}
	 * 			]
	 * 		},
	 * 		...
	 * 		{}
	 * ]
	 * </pre>
	 * 
	 * Note that this API directly set the value to the view objects, and once Visual Style applied, 
	 * those values are overridden by the Visual Style.
	 * 
	 * @summary Update node/edge view objects at once
	 * 
	 * @param networkId Network SUID
	 * @param viewId Network view SUID
	 * @param objectType Type of objects ("nodes" or "edges")
	 * 
	 */
	@PUT
	@Path("/{viewId}/{objectType}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response updateViews(@PathParam("networkId") Long networkId,
			@PathParam("viewId") Long viewId,
			@PathParam("objectType") String objectType, final InputStream is) {

		final CyNetworkView networkView = getView(networkId, viewId);

		final ObjectMapper objMapper = new ObjectMapper();

		try {
			// This should be an JSON array.
			final JsonNode rootNode = objMapper.readValue(is, JsonNode.class);

			for (JsonNode entry : rootNode) {
				final Long objectId = entry.get(CyIdentifiable.SUID).asLong();
				final JsonNode viewNode = entry.get("view");
				if(objectId == null || viewNode == null) {
					continue;
				}

				View<? extends CyIdentifiable> view = null;
				if (objectType.equals("nodes")) {
					view = networkView.getNodeView(networkView.getModel().getNode(objectId));
				} else if (objectType.equals("edges")) {
					view = networkView.getNodeView(networkView.getModel().getNode(objectId));
				} else if(objectType.equals("network")) {
					view = networkView;
				} else {
					throw getError("Method not supported.",
							new IllegalStateException(),
							Response.Status.INTERNAL_SERVER_ERROR);
				}

				if (view == null) {
					throw getError("Could not find view.",
							new IllegalArgumentException(),
							Response.Status.NOT_FOUND);
				}
				styleMapper.updateView(view, viewNode, getLexicon());
			}
			
			// Repaint
			networkView.updateView();
		} catch (Exception e) {
			throw getError(
					"Could not parse the input JSON for updating view because: "
							+ e.getMessage(), e,
					Response.Status.INTERNAL_SERVER_ERROR);
		}
		return Response.ok().build();
	}


	/**
	 * By passing a list of key-value pair for each Visual Property, update single node/edge view.
	 * 
	 * The body should have the following JSON:
	 * 
	 * <pre>
	 * [
	 * 		{
	 * 			"visualProperty": "Visual Property Name, like NODE_FILL_COLOR",
	 * 			"value": "Serialized form of value, like 'red.'"
	 * 		},
	 * 		...
	 * 		{}
	 * ]
	 * </pre>
	 * 
	 * Note that this API directly set the value to the view objects, and once Visual Style applied, 
	 * those values are overridden by the Visual Style.
	 * 
	 * @summary Update single node/edge view object
	 * 
	 * @param networkId Network SUID
	 * @param viewId Network view SUID
	 * @param objectType Type of objects (nodes, edges, or network)
	 * @param objectId node/edge SUID (NOT node/edge view SUID)
	 * 
	 */
	@PUT
	@Path("/{viewId}/{objectType}/{objectId}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response updateView(@PathParam("networkId") Long networkId, @PathParam("viewId") Long viewId,
			@PathParam("objectType") String objectType, @PathParam("objectId") Long objectId,
			final InputStream is) {
		
		final CyNetworkView networkView = getView(networkId, viewId);
		
		View<? extends CyIdentifiable> view = null;
		if(objectType.equals("nodes")) {
			view = networkView.getNodeView(networkView.getModel().getNode(objectId));
		} else if(objectType.equals("edges")) {
			view = networkView.getEdgeView(networkView.getModel().getEdge(objectId));
		} else if(objectType.equals("network")) {
			view = networkView;
		}
		
		if(view == null) {
			throw getError("Could not find view.", new IllegalArgumentException(), Response.Status.NOT_FOUND);
		}
		
		final ObjectMapper objMapper = new ObjectMapper();

		try {
			// This should be an JSON array.
			final JsonNode rootNode = objMapper.readValue(is, JsonNode.class);
			styleMapper.updateView(view, rootNode, getLexicon());
		} catch (Exception e) {
			throw getError("Could not parse the input JSON for updating view because: " + e.getMessage(), e, Response.Status.INTERNAL_SERVER_ERROR);
		}
		// Repaint
		networkView.updateView();
		return Response.ok().build();
	}

	/**
	 * 
	 * By passing a list of key-value pairs for each Visual Property, update network view.
	 * 
	 * The body should have the following JSON:
	 * 
	 * <pre>
	 * [
	 * 		{
	 * 			"visualProperty": "Visual Property Name, like NETWORK_BACKGROUND_PAINT",
	 * 			"value": "Serialized form of value, like 'red.'"
	 * 		},
	 * 		...
	 * 		{}
	 * ]
	 * </pre>
	 * 
	 * Note that this API directly set the value to the view objects, and once Visual Style applied, 
	 * those values are overridden by the Visual Style.
	 * 
	 * @summary Update single network view value, such as background color or zoom level.
	 * 
	 * @param networkId Network SUID
	 * @param viewId Network view SUID
	 * 
	 */
	@PUT
	@Path("/{viewId}/network")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response updateNetworkView(@PathParam("networkId") Long networkId, @PathParam("viewId") Long viewId, final InputStream is) {
		final CyNetworkView networkView = getView(networkId, viewId);
		final ObjectMapper objMapper = new ObjectMapper();

		try {
			// This should be an JSON array.
			final JsonNode rootNode = objMapper.readValue(is, JsonNode.class);
			styleMapper.updateView(networkView, rootNode, getLexicon());
		} catch (Exception e) {
			throw getError("Could not parse the input JSON for updating view because: " + e.getMessage(), e, Response.Status.INTERNAL_SERVER_ERROR);
		}
		// Repaint
		networkView.updateView();
		
		return Response.ok().build();
	}


	/**
	 * @summary Get view object for the specified type (node or edge)
	 * 
	 * @param networkId Network SUID
	 * @param viewId Network view SUID
	 * @param objectType nodes or edges
	 * @param objectId Object's SUID
	 * 
	 */
	@GET
	@Path("/{viewId}/{objectType}/{objectId}")
	@Produces(MediaType.APPLICATION_JSON)
	public String getView(@PathParam("networkId") Long networkId, @PathParam("viewId") Long viewId,
			@PathParam("objectType") String objectType, @PathParam("objectId") Long objectId) {
		final CyNetworkView networkView = getView(networkId, viewId);
		
		View<? extends CyIdentifiable> view = null;
		Collection<VisualProperty<?>> lexicon = null;
		if(nodeLexicon == null) {
			initLexicon();
		}
		
		if(objectType.equals("nodes")) {
			view = networkView.getNodeView(networkView.getModel().getNode(objectId));
			lexicon = nodeLexicon;
		} else if(objectType.equals("edges")) {
			view = networkView.getNodeView(networkView.getModel().getNode(objectId));
			lexicon = edgeLexicon;
		}
		
		if(view == null) {
			throw getError("Could not find view.", new IllegalArgumentException(), Response.Status.NOT_FOUND);
		}

		try {
			return styleSerializer.serializeView(view, lexicon);
		} catch (IOException e) {
			throw getError("Could not serialize the view object.", e, Response.Status.INTERNAL_SERVER_ERROR);
		}
	}


	@GET
	@Path("/{viewId}/{objectType}/{objectId}/{visualProperty}")
	@Produces(MediaType.APPLICATION_JSON)
	public String getSingleVisualPropertyValue(@PathParam("networkId") Long networkId, @PathParam("viewId") Long viewId,
			@PathParam("objectType") String objectType, @PathParam("objectId") Long objectId, 
			@PathParam("visualProperty") String visualProperty) {
		final CyNetworkView networkView = getView(networkId, viewId);
		
		Collection<VisualProperty<?>> vps = null;
		View<? extends CyIdentifiable> view = null;
		if(nodeLexicon == null) {
			initLexicon();
		}
		
		if(objectType.equals("nodes")) {
			view = networkView.getNodeView(networkView.getModel().getNode(objectId));
			vps = nodeLexicon;
		} else if(objectType.equals("edges")) {
			view = networkView.getNodeView(networkView.getModel().getNode(objectId));
			vps = edgeLexicon;
		}
		
		if(view == null) {
			throw getError("Could not find view.", new IllegalArgumentException(), Response.Status.NOT_FOUND);
		}

		return getSingleVp(visualProperty, view, vps);
	}

	@GET
	@Path("/{viewId}/network/{visualProperty}")
	@Produces(MediaType.APPLICATION_JSON)
	public String getNetworkView(@PathParam("networkId") Long networkId, @PathParam("viewId") Long viewId,
			@PathParam("visualProperty") String visualProperty) {
		final CyNetworkView networkView = getView(networkId, viewId);
		
		if(nodeLexicon == null) {
			initLexicon();
		}
		return getSingleVp(visualProperty, networkView, networkLexicon);
	}
	
	private String getSingleVp(final String visualProperty, final View<? extends CyIdentifiable> view, 
			final Collection<VisualProperty<?>> vps) {
		VisualProperty<?> targetVp = null;
		for(final VisualProperty<?> vp: vps) {
			if(vp.getIdString().equals(visualProperty)) {
				targetVp = vp;
				break;
			}
		}
		if(targetVp == null) {
			throw getError("Could not find such Visual Property: " + visualProperty , new NotFoundException(), Response.Status.NOT_FOUND);
		}

		try {
			return styleSerializer.serializeSingleVisualProp(view, targetVp);
		} catch (IOException e) {
			throw getError("Could not serialize the view object.", e, Response.Status.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * @summary Get current values for a specific Visual Property 
	 * 
	 * @param networkId Network SUID
	 * @param viewId SUID of network view
	 * @param objectType nodes or edges
	 * 
	 * @param visualProperty Unique name of a Visual Property
	 */
	@GET
	@Path("/{viewId}/{objectType}")
	@Produces(MediaType.APPLICATION_JSON)
	public String getViews(@PathParam("networkId") Long networkId, @PathParam("viewId") Long viewId,
			@PathParam("objectType") String objectType, @QueryParam("visualProperty") String visualProperty) {
		
		if(visualProperty != null) {
			return getSingleVisualPropertyOfViews(networkId, viewId, objectType, visualProperty);
		}
		
		if(nodeLexicon == null) {
			initLexicon();
		}
		
		Collection<VisualProperty<?>> vps = null;
		if(objectType.equals("nodes")) {
			vps = nodeLexicon;
		} else if(objectType.equals("edges")) {
			vps = edgeLexicon;
		} else if(objectType.equals("network")) {
			vps = networkLexicon;
		}
		
		return getViewForVPList(networkId, viewId, objectType, vps);
	}

	private final String getViewForVPList(final Long networkId, final Long viewId, final String objectType, Collection<VisualProperty<?>> vps) {
		final CyNetworkView networkView = getView(networkId, viewId);
		Collection<? extends View<? extends CyIdentifiable>> graphObjects = null;
		
		if(objectType.equals("nodes")) {
			graphObjects = networkView.getNodeViews();
		} else if(objectType.equals("edges")) {
			graphObjects = networkView.getEdgeViews();
		} else if(objectType.equals("network")) {
			try {
				return styleSerializer.serializeView(networkView, vps);
			} catch (IOException e) {
				throw getError("Could not serialize the view object.", e, Response.Status.INTERNAL_SERVER_ERROR);
			}
		} 
		
		if(graphObjects == null || graphObjects.isEmpty()) {
			throw getError("Could not find views.", new IllegalArgumentException(), Response.Status.NOT_FOUND);
		}
		try {
			return styleSerializer.serializeViews(graphObjects, vps);
		} catch (IOException e) {
			throw getError("Could not serialize the view object.", e, Response.Status.INTERNAL_SERVER_ERROR);
		}
	}	

	
	private final String getSingleVisualPropertyOfViews(Long networkId, Long viewId,
			String objectType, String visualPropertyName) {
		if(nodeLexicon == null) {
			initLexicon();
		}
	
		final Collection<VisualProperty<?>> vps = new HashSet<>();
		VisualProperty<?> vp = null;
		
		if(objectType.equals("nodes")) {
			vp = lexicon.lookup(CyNode.class, visualPropertyName);
		} else if(objectType.equals("edges")) {
			vp = lexicon.lookup(CyEdge.class, visualPropertyName);
		}
		
		if(vp == null) {
			throw getError("Visual Property does not exist: " + visualPropertyName, new NotFoundException(), Response.Status.NOT_FOUND);
		}
		
		vps.add(vp);
		return getViewForVPList(networkId, viewId, objectType, vps);

	}
	
	private final CyNetworkView getView(Long networkId, Long viewId) {
		final Collection<CyNetworkView> views = this.getCyNetworkViews(networkId);
		for (final CyNetworkView view : views) {
			final Long vid = view.getSUID();
			if (vid.equals(viewId)) {
				return view;
			}
		}
		throw new NotFoundException("Could not find view: " + viewId);
	}
}