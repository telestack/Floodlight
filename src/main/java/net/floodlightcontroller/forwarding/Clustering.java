package net.floodlightcontroller.forwarding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.annotations.LogMessageCategory;
import net.floodlightcontroller.core.annotations.LogMessageDoc;
import net.floodlightcontroller.core.annotations.LogMessageDocs;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.MatchUtils;

import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionPopMpls;
import org.projectfloodlight.openflow.protocol.action.OFActionPopVlan;
import org.projectfloodlight.openflow.protocol.action.OFActionPushMpls;
import org.projectfloodlight.openflow.protocol.action.OFActionPushVlan;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.U32;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.U8;
import org.projectfloodlight.openflow.types.VlanVid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@LogMessageCategory("Cluster Programming")
public class Clustering extends ForwardingBase implements IFloodlightModule, IOFSwitchListener {
	protected static Logger log = LoggerFactory.getLogger(Clustering.class);

	protected Map<DatapathId, Tuple<Integer, HashMap<DatapathId, Integer>>> clusterDatabase;
	protected Integer labels;

	@Override
	@LogMessageDoc(level = "ERROR", message = "Unexpected decision made for this packet-in={}", explanation = "An unsupported PacketIn decision has been "
			+ "passed to the flow programming component", recommendation = LogMessageDoc.REPORT_CONTROLLER_BUG)
	public Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi,
			IRoutingDecision decision, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
				IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		// We found a routing decision (i.e. Firewall is enabled... it's the
		// only thing that makes RoutingDecisions)
		if (decision != null) {
			if (log.isTraceEnabled()) {
				log.trace("Forwaring decision={} was made for PacketIn={}",
						decision.getRoutingAction().toString(), pi);
			}

			switch (decision.getRoutingAction()) {
			case NONE:
				// don't do anything
				return Command.CONTINUE;
			case FORWARD_OR_FLOOD:
			case FORWARD:

				doForwardFlow(sw, pi, cntx, false);
				return Command.CONTINUE;
			case MULTICAST:
				// treat as broadcast
				return Command.CONTINUE;
			case DROP:
				return Command.CONTINUE;
			default:
				log.error("Unexpected decision made for this packet-in={}", pi,
						decision.getRoutingAction());
				return Command.CONTINUE;
			}
		} else { // No routing decision was found. Forward to destination or
					// flood if bcast or mcast.
			if (log.isTraceEnabled()) {
				log.trace("No decision was made for PacketIn={}, forwarding",
						pi);
			}

			if (eth.isBroadcast() || eth.isMulticast()) {
				return Command.CONTINUE;
			} else {
				return doForwardFlow(sw, pi, cntx, false);
			}
			/*
			 * if (eth.isMulticast()) { doFlood(sw, pi, cntx); } else {
			 * 
			 * }
			 */
		}

	}

	protected Command doForwardFlow(IOFSwitch sw, OFPacketIn pi,
			FloodlightContext cntx, boolean requestFlowRemovedNotifn) {
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi
				.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
		// Check if we have the location of the destination
		IDevice dstDevice = IDeviceService.fcStore.get(cntx,
				IDeviceService.CONTEXT_DST_DEVICE);

		if (dstDevice != null) {
			IDevice srcDevice = IDeviceService.fcStore.get(cntx,
					IDeviceService.CONTEXT_SRC_DEVICE);
			DatapathId srcIsland = topologyService.getL2DomainId(sw.getId());

			if (srcDevice == null) {
				log.debug("No device entry found for source device");
				return Command.STOP;
			}
			if (srcIsland == null) {
				log.debug("No openflow island found for source {}/{}", sw
						.getId().toString(), inPort);
				return Command.STOP;
			}

			// Validate that we have a destination known on the same island
			// Validate that the source and destination are not on the same
			// switchport
			boolean on_same_island = false;
			boolean on_same_if = false;
			for (SwitchPort dstDap : dstDevice.getAttachmentPoints()) {
				DatapathId dstSwDpid = dstDap.getSwitchDPID();
				DatapathId dstIsland = topologyService.getL2DomainId(dstSwDpid);
				if ((dstIsland != null) && dstIsland.equals(srcIsland)) {
					on_same_island = true;
					if (sw.getId().equals(dstSwDpid)
							&& inPort.equals(dstDap.getPort())) {
						on_same_if = true;
					}
					break;
				}
			}

			if (!on_same_island) {
				// Flood since we don't know the dst device
				if (log.isTraceEnabled()) {
					log.trace("No first hop island found for destination "
							+ "device {}, Action = flooding", dstDevice);
				}
				return Command.CONTINUE;
			}

			if (on_same_if) {
				if (log.isTraceEnabled()) {
					log.trace("Both source and destination are on the same "
							+ "switch/port {}/{}, Action = NOP", sw.toString(),
							inPort);
				}
				return Command.STOP;
			}

			// Install all the routes where both src and dst have attachment
			// points. Since the lists are stored in sorted order we can
			// traverse the attachment points in O(m+n) time
			SwitchPort[] srcDaps = srcDevice.getAttachmentPoints();
			Arrays.sort(srcDaps, clusterIdComparator);
			SwitchPort[] dstDaps = dstDevice.getAttachmentPoints();
			Arrays.sort(dstDaps, clusterIdComparator);

			int iSrcDaps = 0, iDstDaps = 0;

			while ((iSrcDaps < srcDaps.length) && (iDstDaps < dstDaps.length)) {
				SwitchPort srcDap = srcDaps[iSrcDaps];
				SwitchPort dstDap = dstDaps[iDstDaps];

				// srcCluster and dstCluster here cannot be null as
				// every switch will be at least in its own L2 domain.
				DatapathId srcCluster = topologyService.getL2DomainId(srcDap
						.getSwitchDPID());
				DatapathId dstCluster = topologyService.getL2DomainId(dstDap
						.getSwitchDPID());

				int srcVsDest = srcCluster.compareTo(dstCluster);
				if (srcVsDest == 0) {
					if (!srcDap.equals(dstDap)) {
						U64 cookie = AppCookie
								.makeCookie(
										FORWARDING_APP_ID,
										IFloodlightProviderService.bcStore
												.get(cntx,
														IFloodlightProviderService.CONTEXT_PI_PAYLOAD)
												.getVlanID());
						if (clusterDatabase.get(dstDap.getSwitchDPID()).getY().get(srcDap.getSwitchDPID()) == null) {
							if(!this.buildCore(srcDap, dstDap, inPort, sw, cookie,
									pi, cntx)) return Command.CONTINUE;
						}

						IOFSwitch sw1 = switchService.getSwitch(srcDap
								.getSwitchDPID());
						IOFSwitch sw2 = switchService.getSwitch(dstDap
								.getSwitchDPID());
						if(sw1.getId().equals(sw2.getId())){
							return Command.CONTINUE;
							
						}
						OFPort outPort1 = OFPort
								.of(clusterDatabase.get(dstDap.getSwitchDPID()).getY()
										.get(srcDap.getSwitchDPID()));
						OFPort outPort2 = dstDap.getPort();
						Integer label = clusterDatabase.get(dstDap
								.getSwitchDPID()).getX();
						Match iMatch = createMatchFromPacket(sw1, inPort, cntx,
								true, null);
						Match oMatch = createMatchFromPacket(sw2, inPort, cntx,
								false, label);

						pushMods(sw1, iMatch, outPort1, cookie, true, label);
						pushMods(sw2, oMatch, outPort2, cookie, false, null);

					}
					iSrcDaps++;
					iDstDaps++;
				} else if (srcVsDest < 0) {
					iSrcDaps++;
				} else {
					iDstDaps++;
				}
			}
			return Command.STOP;
		} else {
			// Flood since we don't know the dst device
			return Command.STOP;
		}
		
	}

	protected boolean buildCore(SwitchPort srcDap, SwitchPort dstDap,
			OFPort inPort, IOFSwitch sw, U64 cookie, OFPacketIn pi,
			FloodlightContext cntx) {

		Route route = routingEngineService.getRoute(srcDap.getSwitchDPID(),
				srcDap.getPort(), dstDap.getSwitchDPID(), dstDap.getPort(),
				U64.of(0)); // cookie = 0, i.e., default route
		if (route != null) {
			if (log.isTraceEnabled()) {
				log.trace("pushLabeledRoute inPort={} route={} "
						+ "destination={}:{}", new Object[] { inPort, route,
						dstDap.getSwitchDPID(), dstDap.getPort() });
			}

			OFPort port = route.getPath().get(1).getPortId();
			clusterDatabase.get(dstDap.getSwitchDPID()).getY().put(
					srcDap.getSwitchDPID(), port.getPortNumber());
			Integer puerto =clusterDatabase.get(dstDap.getSwitchDPID()).getY().get(
					srcDap.getSwitchDPID());
			Integer label = clusterDatabase.get(dstDap.getSwitchDPID()).getX();

			Match.Builder mb = sw.getOFFactory().buildMatch();
			mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(label));
			/*
			mb.setExact(MatchField.ETH_TYPE, EthType.MPLS_UNICAST);
			mb.setExact(MatchField.MPLS_LABEL, U32.ofRaw(label));
			//mb.setExact(MatchField.MPLS_TC,U8.ZERO);
			 * */
			pushLabeledRoute(route, mb.build(), pi, sw.getId(), cookie, cntx,
					false, false, OFFlowModCommand.ADD);
			return true;
		}
		return false;
	}

	protected Boolean pushLabeledRoute(Route route, Match match, OFPacketIn pi,
			DatapathId pinSwitch, U64 cookie, FloodlightContext cntx,
			boolean reqeustFlowRemovedNotifn, boolean doFlush,
			OFFlowModCommand flowModCommand) {
		boolean srcSwitchIncluded = false;

		List<NodePortTuple> switchPortList = route.getPath();

		for (int indx = switchPortList.size() - 3; indx > 2; indx -= 2) {
			// indx and indx-1 will always have the same switch DPID.
			DatapathId switchDPID = switchPortList.get(indx).getNodeId();
			IOFSwitch sw = switchService.getSwitch(switchDPID);

			if (sw == null) {
				if (log.isWarnEnabled()) {
					log.warn("Unable to push route, switch at DPID {} "
							+ "not available", switchDPID);
				}
				return srcSwitchIncluded;
			}

			// need to build flow mod based on what type it is. Cannot set
			// command later
			OFFlowMod.Builder fmb;
			switch (flowModCommand) {
			case ADD:
				fmb = sw.getOFFactory().buildFlowAdd();
				break;
			case DELETE:
				fmb = sw.getOFFactory().buildFlowDelete();
				break;
			case DELETE_STRICT:
				fmb = sw.getOFFactory().buildFlowDeleteStrict();
				break;
			case MODIFY:
				fmb = sw.getOFFactory().buildFlowModify();
				break;
			default:
				log.error("Could not decode OFFlowModCommand. Using MODIFY_STRICT. (Should another be used as the default?)");
			case MODIFY_STRICT:
				fmb = sw.getOFFactory().buildFlowModifyStrict();
				break;
			}

			OFActionOutput.Builder aob = sw.getOFFactory().actions()
					.buildOutput();
			List<OFAction> actions = new ArrayList<OFAction>();
			//Match.Builder mb = MatchUtils.createRetentiveBuilder(match);

			// set input and output ports on the switch
			OFPort outPort = switchPortList.get(indx).getPortId();
			// OFPort inPort = switchPortList.get(indx - 1).getPortId();
			// mb.setExact(MatchField.IN_PORT, inPort);
			aob.setPort(outPort);
			aob.setMaxLen(Integer.MAX_VALUE);
			actions.add(aob.build());

			if (FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG) {
				Set<OFFlowModFlags> flags = new HashSet<>();
				flags.add(OFFlowModFlags.SEND_FLOW_REM);
				fmb.setFlags(flags);
			}
			// compile
			fmb.setMatch(match)
					// was match w/o modifying input port
					.setActions(actions).setIdleTimeout(0)
					.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
					.setBufferId(OFBufferId.NO_BUFFER).setCookie(cookie)
					.setOutPort(outPort).setPriority(3);

			if (log.isTraceEnabled()) {
				log.trace("Pushing Route flowmod routeIndx={} "
						+ "sw={} inPort={} outPort={}", new Object[] {
						indx, sw, fmb.getMatch().get(MatchField.IN_PORT),
						outPort });
			}
			sw.write(fmb.build());
			//messageDamper.write(sw, fmb.build());
			if (doFlush) {
				sw.flush();
			}

			// Push the packet out the source switch
			if (sw.getId().equals(pinSwitch)) {
				// TODO: Instead of doing a packetOut here we could also
				// send a flowMod with bufferId set....
				pushPacket(sw, pi, false, outPort, cntx);
				srcSwitchIncluded = true;
			}
		}

		return srcSwitchIncluded;
	}

	protected void pushMods(IOFSwitch sw, Match match, OFPort outPort,
			U64 cookie, boolean ingress, Integer label) {

		OFFactory swFactory = sw.getOFFactory();
		OFActions actions = swFactory.actions();
		OFActionOutput.Builder aob = swFactory.actions().buildOutput();
		OFFlowMod.Builder fmb = swFactory.buildFlowAdd();
		List<OFAction> actionList = new ArrayList<OFAction>();
		Match.Builder mb = MatchUtils.createRetentiveBuilder(match);

		// OFPort outPort = swp1.getPort();

		aob.setPort(outPort);
		aob.setMaxLen(Integer.MAX_VALUE);
		if (ingress) {
			OFActionPushVlan vlan =actions.pushVlan(EthType.of(0x8100));
			actionList.add(vlan);
			OFOxms oxms =swFactory.oxms();
	        OFActionSetField vlanid=actions.buildSetField().setField(oxms.buildVlanVid().setValue(OFVlanVidMatch.ofVlan(label)).build()).build();
	        actionList.add(vlanid);
			//MPLS nos fallo :(
			/*
			OFActionPushMpls pushMpls = actions.pushMpls(EthType.MPLS_UNICAST);
			actionList.add(pushMpls);
			OFOxms oxms =swFactory.oxms();
	        OFActionSetField mplsLabel=actions.buildSetField().setField(oxms.buildMplsLabel().setValue(U32.ofRaw(label)).build()).build();
	        actionList.add(mplsLabel);
	        */
			/*actionList.add(swFactory.actions().setField(
					swFactory.oxms().mplsTc(U8.ZERO)));*/
					
		} else {
			/*
			OFActionPopMpls popMpls = actions.popMpls((EthType.MPLS_UNICAST));
			actionList.add(popMpls);
			*/
			OFActionPopVlan vlan =actions.popVlan();
			actionList.add(vlan);

		}
		actionList.add(aob.build());

		OFInstructions inst=swFactory.instructions(); 
        OFInstructionApplyActions apply=inst.buildApplyActions().setActions(actionList).build();
        ArrayList<OFInstruction> instList= new ArrayList<OFInstruction>();
        instList.add(apply);
		fmb.setMatch(mb.build())
				// was match w/o modifying input port
				.setInstructions(instList).setIdleTimeout(200)
				.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
				.setBufferId(OFBufferId.NO_BUFFER).setCookie(cookie)
				.setOutPort(outPort).setPriority(FLOWMOD_DEFAULT_PRIORITY);
		sw.write(fmb.build());

	}

	/**
	 * Instead of using the Firewall's routing decision Match, which might be as
	 * general as "in_port" and inadvertently Match packets erroneously,
	 * construct a more specific Match based on the deserialized OFPacketIn's
	 * payload, which has been placed in the FloodlightContext already by the
	 * Controller.
	 * 
	 * @param sw
	 *            , the switch on which the packet was received
	 * @param inPort
	 *            , the ingress switch port on which the packet was received
	 * @param cntx
	 *            , the current context which contains the deserialized packet
	 * @return a composed Match object based on the provided information
	 */
	protected Match createMatchFromPacket(IOFSwitch sw, OFPort inPort,
			FloodlightContext cntx, boolean ingress, Integer label) {
		// The packet in match will only contain the port number.
		// We need to add in specifics for the hosts we're routing between.
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
				IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		VlanVid vlan = VlanVid.ofVlan(eth.getVlanID());
		MacAddress dstMac = eth.getDestinationMACAddress();

		Match.Builder mb = sw.getOFFactory().buildMatch();
		//mb.setExact(MatchField.IN_PORT, inPort);
		if (FLOWMOD_DEFAULT_MATCH_MAC) {
			mb.setExact(MatchField.ETH_DST, dstMac);
		}
		if (!ingress) {
			mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(label));
			//mb.setExact(MatchField.MPLS_LABEL, U32.ofRaw(label));
		}
		if (FLOWMOD_DEFAULT_MATCH_VLAN) {
			if (!vlan.equals(VlanVid.ZERO)) {
				// mb.setExact(MatchField.VLAN_VID,
				// OFVlanVidMatch.ofVlanVid(vlan));
			}
		}

		// TODO Detect switch type and match to create hardware-implemented flow
		// TODO Allow for IPv6 matches
		if (eth.getEtherType() == EthType.ARP) { /*
												 * shallow check for equality is
												 * okay for EthType
												 */
			mb.setExact(MatchField.ETH_TYPE, EthType.ARP);
		}
		return mb.build();
	}

	

	// IFloodlightModule methods

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// We don't export any services
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// We don't have any services
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IDeviceService.class);
		l.add(IRoutingService.class);
		l.add(ITopologyService.class);
		l.add(IDebugCounterService.class);
		return l;
	}

	@Override
	@LogMessageDocs({
			@LogMessageDoc(level = "WARN", message = "Error parsing flow idle timeout, "
					+ "using default of {number} seconds", explanation = "The properties file contains an invalid "
					+ "flow idle timeout", recommendation = "Correct the idle timeout in the "
					+ "properties file."),
			@LogMessageDoc(level = "WARN", message = "Error parsing flow hard timeout, "
					+ "using default of {number} seconds", explanation = "The properties file contains an invalid "
					+ "flow hard timeout", recommendation = "Correct the hard timeout in the "
					+ "properties file.") })
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		super.init();
		this.clusterDatabase= new HashMap<DatapathId, Clustering.Tuple<Integer,HashMap<DatapathId,Integer>>>();
		this.labels=0;
		this.floodlightProviderService = context
				.getServiceImpl(IFloodlightProviderService.class);
		this.deviceManagerService = context
				.getServiceImpl(IDeviceService.class);
		this.routingEngineService = context
				.getServiceImpl(IRoutingService.class);
		this.topologyService = context.getServiceImpl(ITopologyService.class);
		this.debugCounterService = context
				.getServiceImpl(IDebugCounterService.class);
		this.switchService = context.getServiceImpl(IOFSwitchService.class);

		Map<String, String> configParameters = context.getConfigParams(this);
		String tmp = configParameters.get("hard-timeout");
		if (tmp != null) {
			FLOWMOD_DEFAULT_HARD_TIMEOUT = Integer.parseInt(tmp);
			log.info("Default hard timeout set to {}.",
					FLOWMOD_DEFAULT_HARD_TIMEOUT);
		} else {
			log.info("Default hard timeout not configured. Using {}.",
					FLOWMOD_DEFAULT_HARD_TIMEOUT);
		}
		tmp = configParameters.get("idle-timeout");
		if (tmp != null) {
			FLOWMOD_DEFAULT_IDLE_TIMEOUT = Integer.parseInt(tmp);
			log.info("Default idle timeout set to {}.",
					FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		} else {
			log.info("Default idle timeout not configured. Using {}.",
					FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		}
		tmp = configParameters.get("priority");
		if (tmp != null) {
			FLOWMOD_DEFAULT_PRIORITY = Integer.parseInt(tmp);
			log.info("Default priority set to {}.", FLOWMOD_DEFAULT_PRIORITY);
		} else {
			log.info("Default priority not configured. Using {}.",
					FLOWMOD_DEFAULT_PRIORITY);
		}
		tmp = configParameters.get("set-send-flow-rem-flag");
		if (tmp != null) {
			FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG = Boolean.parseBoolean(tmp);
			log.info("Default flags will be set to SEND_FLOW_REM.");
		} else {
			log.info("Default flags will be empty.");
		}
		tmp = configParameters.get("match");
		if (tmp != null) {
			tmp = tmp.toLowerCase();
			if (!tmp.contains("vlan") && !tmp.contains("mac")
					&& !tmp.contains("ip") && !tmp.contains("port")) {
				/*
				 * leave the default configuration -- blank or invalid 'match'
				 * value
				 */
			} else {
				FLOWMOD_DEFAULT_MATCH_VLAN = tmp.contains("vlan") ? true
						: false;
				FLOWMOD_DEFAULT_MATCH_MAC = tmp.contains("mac") ? true : false;
				FLOWMOD_DEFAULT_MATCH_IP_ADDR = tmp.contains("ip") ? true
						: false;
				FLOWMOD_DEFAULT_MATCH_TRANSPORT = tmp.contains("port") ? true
						: false;

			}
		}
		log.info("Default flow matches set to: VLAN="
				+ FLOWMOD_DEFAULT_MATCH_VLAN + ", MAC="
				+ FLOWMOD_DEFAULT_MATCH_MAC + ", IP="
				+ FLOWMOD_DEFAULT_MATCH_IP_ADDR + ", TPPT="
				+ FLOWMOD_DEFAULT_MATCH_TRANSPORT);

	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		super.startUp();
		switchService.addOFSwitchListener(this);
	}

	public class Tuple<X, Y> {
		private  X x;
		private  Y y;

		public Tuple(X x, Y y) {
			this.x = x;
			this.y = y;
		}
		public Y getY() {
			return y;
		}	
		public X getX() {
			    	return x;
			    }	
		public void setX(X x) {
	    	this.x=x;
	    }	
		public void setY(Y y) {
			this.y=y;
	    }	

	}
	
	@Override
	public String getName() {
		return "clustering";
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return (type.equals(OFType.PACKET_IN) && (name.equals("topology") || name.equals("devicemanager")));
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return (type.equals(OFType.PACKET_IN) && (name.equals("forwarding")));
	}

	@Override
	public void switchAdded(DatapathId switchId) {
		labels++;
		clusterDatabase.put(switchId,new Tuple<>(labels, new HashMap<DatapathId,Integer>()));
		for (Map.Entry<DatapathId, Tuple<Integer, HashMap<DatapathId, Integer>>> entry : clusterDatabase.entrySet())
		{
		    if(entry.getKey()!=switchId){
		    	entry.getValue().getY().put(switchId, null);		    	
		    }
		}
	}

	@Override
	public void switchRemoved(DatapathId switchId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void switchActivated(DatapathId switchId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void switchPortChanged(DatapathId switchId, OFPortDesc port,
			PortChangeType type) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void switchChanged(DatapathId switchId) {
		// TODO Auto-generated method stub
		
	}
}
