package net.floodlightcontroller.micemanager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.devicemanager.internal.Device;
import net.floodlightcontroller.learningswitch.LearningSwitch;
import net.floodlightcontroller.micemanager.web.MiceManagerWebRoutable;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.topology.TopologyManager;
import org.projectfloodlight.openflow.protocol.OFFactories;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MiceManager implements  IFloodlightModule, IMiceManager{
	protected static Logger log = LoggerFactory.getLogger(MiceManager.class);
	//IFloodlightProviderService floodlightProviderService;
	protected TopologyManager topologyManager;
	protected IOFSwitchService switchService;
	protected IDeviceService deviceManager;
	protected IStaticFlowEntryPusherService sfp;
	protected IRoutingService routingEngine;
	IRestApiService restApi;
	private OFFactory factory = OFFactories.getFactory(OFVersion.OF_13);

	static final int MiceManagerCookie = 32768;

	// Definimos las ips de forma estática.

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		//floodlightProviderService = context
		//		.getServiceImpl(IFloodlightProviderService.class);
		log = LoggerFactory.getLogger(MiceManager.class);
		sfp=context.getServiceImpl(IStaticFlowEntryPusherService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
		deviceManager = context.getServiceImpl(IDeviceService.class);
		restApi = context.getServiceImpl(IRestApiService.class);
	}

	// We search for devices with specifed ips

	// we asign vlan to ips

	public void handleNewMice(String ipadd) {
		IPv4Address ip= IPv4Address.of(ipadd);
		@SuppressWarnings("unchecked")
		Iterator<Device> diter = (Iterator<Device>) deviceManager.queryDevices(
				null, null, ip, null, null);

		List<DatapathId> switches;
		// There should be only one MAC address to the given IP address. In any
		// case,
		// we return only the first MAC address found.
		List<Route> routes = new ArrayList<Route>();
		if (diter.hasNext()) {
			Device device = diter.next();
			switches = this.accessSwitches(device.getAttachmentPoints());
			//por favor llene sus datos
			DatapathId srcDpid = device.getAttachmentPoints()[0]
					.getSwitchDPID();
			OFPort srcPort = device.getAttachmentPoints()[0].getPort();
			MacAddress devMac =device.getMACAddress();
			this.EgressSwitchFlows(srcDpid);
			for (DatapathId sw : switches) {
				Route currentRoute = this.findRoute(srcDpid, srcPort,
						sw, OFPort.of(0));
				routes.add(currentRoute);
				List<NodePortTuple> listaNPT = currentRoute.getPath();
				int i;
				// for (NodePortTuple npt : listaNPT.) {
				NodePortTuple npt = null;
				NodePortTuple prevNpt=null;
				for (i = 0; i < listaNPT.size(); i++) {
					npt=listaNPT.get(i+1);
					if (i == 0) {
						// we are working with the ingress switch

						// first the up dude
						String nameUp="up";
						ArrayList<OFAction> actionList = new ArrayList<OFAction>();
						OFActions actions = factory.actions();
						//el output!!!
						OFActionOutput output = actions.buildOutput()
							    .setMaxLen(0xFFffFFff)
							    .setPort(srcPort)
							    .build();
							actionList.add(output);
						Match match = factory.buildMatch()
						// .setExact(MatchField.IN_PORT, OFPort.of(1))
								.setExact(MatchField.ETH_TYPE, EthType.IPv4)
								// .setExact(MatchField.IPV4_SRC,
								// device.getIPv4Addresses()[0])
								.setExact(MatchField.ETH_DST,
										devMac).build();
						OFFlowMod.Builder fmb;
						fmb = factory.buildFlowAdd();
						fmb.setMatch(match);
						fmb.setCookie((U64.of(0)));
						fmb.setIdleTimeout(0);
						fmb.setHardTimeout(0);
						fmb.setPriority(MiceManagerCookie);
						fmb.setActions(actionList);
						sfp.addFlow(nameUp, fmb.build(), npt.getNodeId());

						// now the 'down'
						String nameDown="down";
						ArrayList<OFAction> actionListdwn = new ArrayList<OFAction>();
						OFActions actionsDwn = factory.actions();
						//el output!!!
						OFActionOutput outputD = actionsDwn.buildOutput()
							    .setMaxLen(0xFFffFFff)
							    .setPort(npt.getPortId())
							    .build();
						actionListdwn.add(outputD);
						Match matchUp = factory
								.buildMatch()
								.setExact(MatchField.ETH_TYPE, EthType.IPv4)
								.setExact(MatchField.ETH_DST,
										MacAddress.of(sw.toString())).build();
						OFFlowMod.Builder fmbD;
						fmbD = factory.buildFlowAdd();
						fmbD.setMatch(matchUp);
						fmbD.setCookie((U64.of(0)));
						fmbD.setIdleTimeout(0);
						fmbD.setHardTimeout(0);
						fmbD.setPriority(MiceManagerCookie);
						fmb.setActions(actionListdwn);
						sfp.addFlow(nameDown, fmbD.build(), prevNpt.getNodeId());
					} else {
						prevNpt = listaNPT.get(i-1);
						// first the up
						String nameUp="up";
						ArrayList<OFAction> actionList = new ArrayList<OFAction>();
						OFActions actions = factory.actions();
						//el output!!!
						OFActionOutput output = actions.buildOutput()
							    .setMaxLen(0xFFffFFff)
							    .setPort(prevNpt.getPortId())
							    .build();
						actionList.add(output);
						//ahora el match
						Match match = factory
								.buildMatch()
								.setExact(MatchField.ETH_TYPE, EthType.IPv4)
								.setExact(MatchField.ETH_DST,
										devMac).build();
						OFFlowMod.Builder fmb;
						fmb = factory.buildFlowAdd();
						fmb.setMatch(match);
						fmb.setCookie((U64
								.of(0)));
						fmb.setIdleTimeout(0);
						fmb.setHardTimeout(0);
						fmb.setPriority(MiceManagerCookie);
						fmb.setActions(actionList);
						sfp.addFlow(nameUp, fmb.build(), npt.getNodeId());

						// now the 'down'
						String nameDown="down";
						ArrayList<OFAction> actionListdwn = new ArrayList<OFAction>();
						OFActions actionsDwn = factory.actions();
						//el output!!!
						OFActionOutput outputD = actionsDwn.buildOutput()
							    .setMaxLen(0xFFffFFff)
							    .setPort(npt.getPortId())
							    .build();
						actionListdwn.add(outputD);
						Match matchDwn = factory.buildMatch()
								// .setExact(MatchField.IN_PORT, OFPort.of(1))
								.setExact(MatchField.ETH_TYPE, EthType.IPv4)
								.setExact(MatchField.ETH_DST,
										MacAddress.of(sw.toString())).build();
						OFFlowMod.Builder fmbD;
						fmbD = factory.buildFlowAdd();
						fmbD.setMatch(matchDwn);
						fmbD.setCookie((U64
								.of(0)));
						fmbD.setIdleTimeout(0);
						fmbD.setHardTimeout(0);
						fmbD.setPriority(MiceManagerCookie);
						sfp.addFlow(nameDown, fmbD.build(), npt.getNodeId());
					}

				}
			}
		}
	}

	public void EgressSwitchFlows(DatapathId swid) {

		// Hacemos un geAllDevices y en cada uno ejecutamos el FlowMod, menos en
		// los que pertenecen al switch Ingress
		for (IDevice device : deviceManager.getAllDevices()) {
			DatapathId dpid = device.getAttachmentPoints()[0].getSwitchDPID();
			if (dpid.equals(swid)) {
				continue;
			}
			OFFlowMod.Builder fm = this.flowMod(EthType.IPv4,
					device.getMACAddress(), device.getIPv4Addresses()[0],
					"flow", device.getAttachmentPoints()[0].getPort());
			IOFSwitch sw = switchService.getSwitch(dpid);
			sw.write(fm.build());
		}
	}

	public OFFlowMod.Builder flowMod(EthType ethType, MacAddress macAddress,
			IPv4Address ip, String name, OFPort forPort) {

		ArrayList<OFAction> actionList = new ArrayList<OFAction>();
		OFActions actions = factory.actions();
		OFOxms oxms = factory.oxms();
		// If we need to rewrite some header
		OFActionSetField setDlDst = actions.buildSetField()
				.setField(oxms.buildEthDst().setValue(macAddress).build())
				.build();
		// We always need to forward to some port
		OFActionOutput output = actions.buildOutput().setPort(forPort).build();
		actionList.add(setDlDst);
		actionList.add(output);
		Match match = factory.buildMatch()
				.setExact(MatchField.ETH_TYPE, ethType)
				.setExact(MatchField.IPV4_DST, ip).build();
		OFFlowMod.Builder fmb;
		fmb = factory.buildFlowAdd();
		fmb.setMatch(match);
		fmb.setCookie((U64.of(LearningSwitch.LEARNING_SWITCH_COOKIE)));
		fmb.setIdleTimeout(0);
		fmb.setHardTimeout(0);
		fmb.setPriority(1);
		return fmb;
	}

	// Encontrar todos los switches de acceso
	public List<DatapathId> accessSwitches(SwitchPort[] ap) {
		List<DatapathId> switches = new ArrayList<DatapathId>();

		for (DatapathId switchId : switchService.getAllSwitchDpids()) {
			IOFSwitch sw = switchService.getSwitch(switchId);
			for (OFPortDesc port : sw.getPorts()) {
				OFPort portId = port.getPortNo();
				if (switchId.equals(ap[0].getSwitchDPID())
						&& (portId.getShortPortNumber() == ap[0].getPort()
								.getShortPortNumber())) {
					continue;
				}
				if (topologyManager.isAttachmentPointPort(switchId,
						port.getPortNo())) {
					switches.add(sw.getId());
					break;
				}
			}
		}
		return switches;

	}

	// We make routes from every access switch to the devices.

	public Route findRoute(DatapathId longSrcDpid, OFPort shortSrcPort,
			DatapathId longDstDpid, OFPort shortDstPort) {


		Route result = routingEngine.getRoute(longSrcDpid, shortSrcPort,
				longDstDpid, shortDstPort, U64.of(0));

		if (result != null) {
			return result;
		} else {
			log.debug("ERROR! no route found");
			return null;
		}
	}

	// for each device, we make the flowmods. On access switch:
	// match with dest, write vlan, output



	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IMiceManager.class);
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>,
		IFloodlightService> m =
		new HashMap<Class<? extends IFloodlightService>,
		IFloodlightService>();
		m.put(IMiceManager.class, this);
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
		        new ArrayList<Class<? extends IFloodlightService>>();
		    //l.add(IFloodlightProviderService.class);
			l.add(IOFSwitchService.class);
			l.add(IDeviceService.class);
			l.add(IRoutingService.class);
			l.add(ITopologyService.class);
		    l.add(IRestApiService.class);
		    l.add(IStaticFlowEntryPusherService.class);
		    return l;
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		restApi.addRestletRoutable(new MiceManagerWebRoutable());
	}

	// on aggregation switches,match with vlan, output
}
