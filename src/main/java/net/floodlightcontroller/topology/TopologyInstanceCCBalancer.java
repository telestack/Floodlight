package net.floodlightcontroller.topology;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import net.floodlightcontroller.routing.BroadcastTree;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.routing.RouteId;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import it.garr.ccbalancer.ICCBalancerService;

public class TopologyInstanceCCBalancer extends TopologyInstance {

	public static final short LT_SH_LINK = 1;
	public static final short LT_BD_LINK = 2;
	public static final short LT_TUNNEL = 3;

	public static final int MAX_LINK_WEIGHT = 10000;
	public static final int MAX_PATH_WEIGHT = Integer.MAX_VALUE
			- MAX_LINK_WEIGHT - 1;
	public static final int PATH_CACHE_SIZE = 1000;

	protected ICCBalancerService mpbalance;

	protected static Logger log = LoggerFactory
			.getLogger(TopologyInstanceCCBalancer.class);

	// States for routing
	protected Map<DatapathId, BroadcastTree> elephantRootedTrees;

	protected class PathCacheLoader extends CacheLoader<RouteId, Route> {
		TopologyInstance ti;

		PathCacheLoader(TopologyInstance ti) {
			this.ti = ti;
		}

		@Override
		public Route load(RouteId rid) {
			return ti.buildroute(rid);
		}
	}

	// Path cache loader is defined for loading a path when it not present
	// in the cache.
	private final PathCacheLoader pathCacheLoader = new PathCacheLoader(this);
	protected LoadingCache<RouteId, Route> pathcache;

	public TopologyInstanceCCBalancer(ICCBalancerService mpbalance) {
		this.switches = new HashSet<DatapathId>();
		this.switchPorts = new HashMap<DatapathId, Set<OFPort>>();
		this.switchPortLinks = new HashMap<NodePortTuple, Set<Link>>();
		this.broadcastDomainPorts = new HashSet<NodePortTuple>();
		this.tunnelPorts = new HashSet<NodePortTuple>();
		this.blockedPorts = new HashSet<NodePortTuple>();
		this.blockedLinks = new HashSet<Link>();
		this.mpbalance = mpbalance;
	}

	public TopologyInstanceCCBalancer(Map<DatapathId, Set<OFPort>> switchPorts,
			Map<NodePortTuple, Set<Link>> switchPortLinks,
			ICCBalancerService mpbalance) {
		this.switches = new HashSet<DatapathId>(switchPorts.keySet());
		this.switchPorts = new HashMap<DatapathId, Set<OFPort>>(switchPorts);
		this.switchPortLinks = new HashMap<NodePortTuple, Set<Link>>(
				switchPortLinks);
		this.broadcastDomainPorts = new HashSet<NodePortTuple>(
				broadcastDomainPorts);
		this.tunnelPorts = new HashSet<NodePortTuple>();
		this.blockedPorts = new HashSet<NodePortTuple>();
		this.blockedLinks = new HashSet<Link>();
		this.mpbalance = mpbalance;

		clusters = new HashSet<Cluster>();
		switchClusterMap = new HashMap<DatapathId, Cluster>();
	}

	public TopologyInstanceCCBalancer(Map<DatapathId, Set<OFPort>> switchPorts,
			Set<NodePortTuple> blockedPorts,
			Map<NodePortTuple, Set<Link>> switchPortLinks,
			Set<NodePortTuple> broadcastDomainPorts,
			Set<NodePortTuple> tunnelPorts, ICCBalancerService mpbalance) {

		// copy these structures
		this.switches = new HashSet<DatapathId>(switchPorts.keySet());
		this.switchPorts = new HashMap<DatapathId, Set<OFPort>>();
		for (DatapathId sw : switchPorts.keySet()) {
			this.switchPorts.put(sw, new HashSet<OFPort>(switchPorts.get(sw)));
		}

		this.blockedPorts = new HashSet<NodePortTuple>(blockedPorts);
		this.switchPortLinks = new HashMap<NodePortTuple, Set<Link>>();
		for (NodePortTuple npt : switchPortLinks.keySet()) {
			this.switchPortLinks.put(npt,
					new HashSet<Link>(switchPortLinks.get(npt)));
		}
		this.broadcastDomainPorts = new HashSet<NodePortTuple>(
				broadcastDomainPorts);
		this.tunnelPorts = new HashSet<NodePortTuple>(tunnelPorts);

		this.mpbalance = mpbalance;

		blockedLinks = new HashSet<Link>();
		clusters = new HashSet<Cluster>();
		switchClusterMap = new HashMap<DatapathId, Cluster>();
		destinationRootedTrees = new HashMap<DatapathId, BroadcastTree>();
		elephantRootedTrees = new HashMap<DatapathId, BroadcastTree>();
		clusterBroadcastTrees = new HashMap<DatapathId, BroadcastTree>();
		clusterBroadcastNodePorts = new HashMap<DatapathId, Set<NodePortTuple>>();
		pathcache = CacheBuilder.newBuilder().concurrencyLevel(4)
				.maximumSize(1000L).build(new CacheLoader<RouteId, Route>() {
					public Route load(RouteId rid) {
						return pathCacheLoader.load(rid);
					}
				});
	}

	protected void calculateShortestPathTreeForElephants() {
		// Este metodo calcula los broadcast trees pero solo para ciertos nodos
		// del cluster.
		// LLenamos el hash elephantRootedTrees
		elephantRootedTrees.clear();
		Map<Link, Integer> linkCost = mpbalance.getLinkCost();

		for (Cluster c : clusters) {
			for (DatapathId node : elephantRootedTrees.keySet()) {
				BroadcastTree tree = dijkstra(c, node, linkCost, true);
				elephantRootedTrees.put(node, tree);
			}
		}

	}

	protected Route buildElephantRoute(RouteId id) {
		// Método llamado por la cache de rutas, cuando no se encuentra la ruta
		// en la cache

		NodePortTuple npt;
		DatapathId srcId = id.getSrc();
		DatapathId dstId = id.getDst();

		LinkedList<NodePortTuple> switchPorts = new LinkedList<NodePortTuple>();

		if (elephantRootedTrees == null)
			return null;
		if (elephantRootedTrees.get(dstId) == null)
			return null;

		Map<DatapathId, Link> nexthoplinks = elephantRootedTrees.get(dstId)
				.getLinks();

		// Since flow is already active somehow, we can skip some verification
		if ((nexthoplinks != null) && (nexthoplinks.get(srcId) != null)) {
			while (!srcId.equals(dstId)) {
				Link l = nexthoplinks.get(srcId);

				npt = new NodePortTuple(l.getSrc(), l.getSrcPort());
				switchPorts.addLast(npt);
				npt = new NodePortTuple(l.getDst(), l.getDstPort());
				switchPorts.addLast(npt);
				srcId = nexthoplinks.get(srcId).getDst();
			}
		}
		// else, no path exists, and path equals null

		Route result = null;
		if (switchPorts != null && !switchPorts.isEmpty()) {
			result = new Route(id, switchPorts);
		}
		if (log.isTraceEnabled()) {
			log.trace("buildElephantRoute: {}", result);
		}
		return result;
	}

	protected Route notVeryImportantElephant(RouteId id) {
		// As we are not going to keep the routes cached, we make all the
		// business here
		NodePortTuple npt;
		DatapathId srcId = id.getSrc();
		DatapathId dstId = id.getDst();
		LinkedList<NodePortTuple> switchPorts = new LinkedList<NodePortTuple>();

		Map<Link, Integer> linkCost = mpbalance.getLinkCost();
		Cluster c = switchClusterMap.get(srcId);
		BroadcastTree tree = dijkstra(c, srcId, linkCost, true);
		Map<DatapathId, Link> nexthoplinks = tree.getLinks();

		if ((nexthoplinks != null) && (nexthoplinks.get(srcId) != null)) {
			while (!srcId.equals(dstId)) {
				Link l = nexthoplinks.get(srcId);
				npt = new NodePortTuple(l.getSrc(), l.getSrcPort());
				switchPorts.addLast(npt);
				npt = new NodePortTuple(l.getDst(), l.getDstPort());
				switchPorts.addLast(npt);
				srcId = nexthoplinks.get(srcId).getDst();
			}
		}
		// else, no path exists, and path equals null

		Route result = null;
		if (switchPorts != null && !switchPorts.isEmpty()) {
			result = new Route(id, switchPorts);
		}
		return result;
	}

	protected Route getElephantRoute(DatapathId srcId, DatapathId dstId) {
		if (srcId.equals(dstId))
			return null;
		RouteId id = new RouteId(srcId, dstId);
		Route result = null;
		if (elephantRootedTrees.get(dstId) == null) {
			return notVeryImportantElephant(id);
		} else {
			try {
				result = pathcache.get(id);
			} catch (Exception e) {
				log.error("{}", e);
			}

			if (log.isTraceEnabled()) {
				log.trace("getRoute: {} -> {}", id, result);
			}
			return result;
		}

	}
	protected void updateCache(){}
	
	protected void addFamousDest(DatapathId sw){
		elephantRootedTrees.put(sw, null);
	}

}
