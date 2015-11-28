package net.floodlightcontroller.topology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;

import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.MultiRoute;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.routing.RouteId;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.asu.emit.qyan.alg.control.YenTopKShortestPathsAlg;
import edu.asu.emit.qyan.alg.model.Graph;
import edu.asu.emit.qyan.alg.model.Path;
import edu.asu.emit.qyan.alg.model.Vertex;
import edu.asu.emit.qyan.alg.model.abstracts.BaseVertex;

public class TopologyInstanceKHopMetric extends TopologyInstance {

	protected class MultirouteCacheLoader extends
			CacheLoader<RouteId, MultiRoute> {
		TopologyInstanceKHopMetric ti;

		MultirouteCacheLoader(TopologyInstanceKHopMetric ti) {
			this.ti = ti;
		}

		@Override
		public MultiRoute load(RouteId rid) {
			return ti.computeMultiRoute(rid);
		}
	}

	private final MultirouteCacheLoader multirouteCacheLoader = new MultirouteCacheLoader(
			this);
	protected LoadingCache<RouteId, MultiRoute> multiRouteCache;
	protected Map<DatapathId, Graph> graphList;

	public TopologyInstanceKHopMetric(Map<DatapathId, Set<OFPort>> switchPorts,
			Map<NodePortTuple, Set<Link>> switchPortLinks,
			Set<NodePortTuple> broadcastDomainPorts) {
		super(switchPorts, switchPortLinks, broadcastDomainPorts);
		multiRouteCache = CacheBuilder.newBuilder().concurrencyLevel(4)
				.maximumSize(1000L)
				.build(new CacheLoader<RouteId, MultiRoute>() {
					public MultiRoute load(RouteId rid) {
						return multirouteCacheLoader.load(rid);
					}
				});
	}

	public TopologyInstanceKHopMetric(Map<DatapathId, Set<OFPort>> switchPorts,
            Set<NodePortTuple> blockedPorts,
            Map<NodePortTuple, Set<Link>> switchPortLinks,
            Set<NodePortTuple> broadcastDomainPorts,
            Set<NodePortTuple> tunnelPorts) {
		super(switchPorts, blockedPorts, switchPortLinks, broadcastDomainPorts,
				tunnelPorts);
		multiRouteCache = CacheBuilder.newBuilder().concurrencyLevel(4)
				.maximumSize(1000L)
				.build(new CacheLoader<RouteId, MultiRoute>() {
					public MultiRoute load(RouteId rid) {
						return multirouteCacheLoader.load(rid);
					}
				});
	}

	private Graph fromClusterToGraph(Cluster c, Map<Link, Integer> linkCost) {
		Graph graph = new Graph();
		graph.set_vertex_num(c.getNodes().size());
		for (DatapathId node : c.getNodes()) {
			BaseVertex vertex = new Vertex(node.getLong());
			graph._vertex_list.add(vertex);
			graph._id_vertex_index.put(vertex.get_id(), vertex);
		}
		for (DatapathId nodeFrom : c.getLinks().keySet()) {
			for (Link l : c.getLinks().get(nodeFrom)) {
				long start_vertex_id = l.getSrc().getLong();
				long end_vertex_id = l.getDst().getLong();
				double weight = 0;
				if (linkCost == null || linkCost.get(l) == null)
					weight = 1;
				else
					weight = linkCost.get(l);
				graph.add_edge(start_vertex_id, end_vertex_id, weight, l
						.getSrcPort().getPortNumber(), l.getDstPort()
						.getPortNumber());
			}
		}
		return graph;
	}


	protected void createGraphs() {

		Map<Link, Integer> linkCost = new HashMap<Link, Integer>();
		int tunnel_weight = switchPorts.size() + 1;

		for (NodePortTuple npt : tunnelPorts) {
			if (switchPortLinks.get(npt) == null)
				continue;
			for (Link link : switchPortLinks.get(npt)) {
				if (link == null)
					continue;
				linkCost.put(link, tunnel_weight);
			}
		}

		for (Cluster c : clusters) {
			graphList.put(c.getId(), fromClusterToGraph(c, linkCost));
		}

	}

	protected MultiRoute computeMultiRoute(RouteId rid) {

		DatapathId src = rid.getSrc();
		DatapathId dst = rid.getSrc();
		Cluster cluster = null;
		Graph graph = null;
		cluster=super.switchClusterMap.get(src);
		if(cluster.equals(switchClusterMap.get(dst))) return null;
		graph = graphList.get(cluster.getId());
		Map<Link, Integer> linkCost = new HashMap<Link, Integer>();
		int tunnel_weight = switchPorts.size() + 1;
		for (NodePortTuple npt : tunnelPorts) {
			if (switchPortLinks.get(npt) == null)
				continue;
			for (Link link : switchPortLinks.get(npt)) {
				if (link == null)
					continue;
				linkCost.put(link, tunnel_weight);
			}
		}
		YenTopKShortestPathsAlg yenAlg = new YenTopKShortestPathsAlg(graph);
		List<Path> pathList = yenAlg.get_shortest_paths(
				graph.get_vertex(src.getLong()),
				graph.get_vertex(dst.getLong()), 3);
		MultiRoute routes = new MultiRoute();
		for (Path path : pathList) {
			Map<DatapathId, Set<Link>> clLinks = cluster.getLinks();
			List<BaseVertex> vertices = path.get_vertices();
			List<NodePortTuple> swithports = new ArrayList<NodePortTuple>();
			for (int i = 0; i < vertices.size(); i++) {
				Set<Link> nodeALinks = clLinks.get(vertices.get(i));
				Set<Link> nodeBLinks = clLinks.get(vertices.get(i + 1));
				DatapathId node = null;
				OFPort port = null;
				Iterator<Link> iterator = nodeALinks.iterator();
				while (iterator.hasNext()) {
					Link setElement = iterator.next();
					if (nodeBLinks.contains(setElement)) {
						node = setElement.getSrc();
						port = setElement.getSrcPort();
						break;
					}
				}
				swithports.add(new NodePortTuple(node, port));
			}
			Route nueva = new Route(rid, swithports);
			routes.addRoute(nueva);
		}
		return routes;
	}

	protected Route getRouteFromMulti(DatapathId srcId, OFPort srcPort,
			DatapathId dstId, OFPort dstPort) {
		if (srcId.equals(dstId))
			return null;
		RouteId id = new RouteId(srcId, dstId);
		Route result = null;
		try {
			result = multiRouteCache.get(id).getRoute();
		} catch (Exception e) {
			log.error("{No route available}", e);
		}
		return result;
	}
}
