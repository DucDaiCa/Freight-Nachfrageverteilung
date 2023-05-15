/* *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2019 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.RunFreight;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freight.FreightConfigGroup;
import org.matsim.contrib.freight.carrier.Carrier;
import org.matsim.contrib.freight.carrier.CarrierPlanXmlWriterV2;
import org.matsim.contrib.freight.carrier.CarrierShipment;
import org.matsim.contrib.freight.carrier.CarrierUtils;
import org.matsim.contrib.freight.controler.CarrierModule;
import org.matsim.contrib.freight.utils.FreightUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.VehicleType;

import java.util.LinkedList;
import java.util.concurrent.ExecutionException;


public class RunFreightExample {

	private static final Logger log = LogManager.getLogger(RunFreightExample.class);

	public static void main(String[] args) throws ExecutionException, InterruptedException{
		long before = System.nanoTime();
		// ### config stuff: ###

		Config config = ConfigUtils.createConfig();
		//Config config = ConfigUtils.loadConfig( IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL( "freight-chessboard-9x9" ), "config.xml" ) );
		//config.plans().setInputFile( null ); // remove passenger input
		config.network().setInputFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/berlin/berlin-v5.5-10pct/input/berlin-v5.5-network.xml.gz");

		// more general settings
		config.controler().setOutputDirectory("./output/freight" );

		config.controler().setLastIteration(0 );		// yyyyyy iterations currently do not work; needs to be fixed.  (Internal discussion at end of file.)

		config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		// freight settings
		FreightConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule( config, FreightConfigGroup.class ) ;
		//freightConfigGroup.setCarriersFile( "singleCarrierFiveActivitiesWithoutRoutes_Shipments.xml");
		freightConfigGroup.setCarriersFile( "input/dummyCarrier.xml");
		//freightConfigGroup.setCarriersVehicleTypesFile( "vehicleTypes.xml");
		freightConfigGroup.setCarriersVehicleTypesFile( "input/dummyVehicleTypes.xml");

		// load scenario (this is not loading the freight material):
		org.matsim.api.core.v01.Scenario scenario = ScenarioUtils.loadScenario( config );

		// load carriers according to freight config
		FreightUtils.loadCarriersAccordingToFreightConfig( scenario );

		// Write out the original carriers file - before any modification is done
		new CarrierPlanXmlWriterV2(FreightUtils.getCarriers( scenario )).write( "output/originalCarriers.xml" ) ;

		// how to set the capacity of the "light" vehicle type to "25":
		//FreightUtils.getCarrierVehicleTypes( scenario ).getVehicleTypes().get( Id.create("light", VehicleType.class ) ).getCapacity().setOther( 25 );

		// What vehicle types do we have
		for(VehicleType vehicleType : FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().values()) {
			log.info(vehicleType.getId()+": "+vehicleType.getCapacity().getOther());
		}

		// changing service demand capacity of existing services
		/*for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
			for (org.matsim.contrib.freight.carrier.CarrierService carrierService: carrier.getServices().values() ){
				int demand = carrierService.getCapacityDemand()*6;
				CarrierService newService = CarrierService.Builder.newInstance(carrierService.getId(),carrierService.getLocationLinkId())
						.setCapacityDemand(demand)
						.setServiceDuration(carrierService.getServiceDuration())
						.setServiceStartTimeWindow(carrierService.getServiceStartTimeWindow())
						.build();
				CarrierUtils.addService(carrier,newService);
			}
		}*/


		// changing shipment size of existing shipment
		for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
			CarrierUtils.setJspritIterations(carrier,5);
			for (CarrierShipment carrierShipment : carrier.getShipments().values()) {
				int size = carrierShipment.getSize()*3;

				CarrierShipment newShipment = createShipment(carrierShipment, 0, size);
				CarrierUtils.addShipment(carrier,newShipment); //add the new shipment to the carrier
			}
		}

		Scenario mySelection =  Scenario.SCENE_1;
		double Boundary_value = Double.MAX_VALUE;
		switch (mySelection) {

			case SCENE_1:
				{
					for(VehicleType vehicleType : FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().values()){
						if(vehicleType.getCapacity().getOther() < Boundary_value)
						{
							Boundary_value = vehicleType.getCapacity().getOther();
						}
					}
				}
				//Boundary_value = Boundary_value/2;
				break;
			case SCENE_2:

				break;
			default:
				throw new IllegalStateException("Unexpected value: " + mySelection);
		}

		//Boundary_value = Boundary_value/2;

		// method to create new shipments
		for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {

			LinkedList<CarrierShipment> newShipments = new LinkedList<>();  // Liste um die "neuen" Shipments temporär zu speichern, weil man sie nicht während des Iterierens hinzufügen kann.
			LinkedList<CarrierShipment> oldShipments = new LinkedList<>(); // Liste um die "alten" Shipments temporär zu speichern
			int demandBefore = 0;
			int demandAfter = 0;

			// counting shipment demand before (method)
			demandBefore = Demand(carrier,demandBefore);

			shipmentCreator((int) Boundary_value, carrier, newShipments, oldShipments);

			// remove the old shipments
			for (CarrierShipment shipmentToRemove : oldShipments) {
				carrier.getShipments().remove(shipmentToRemove.getId(), shipmentToRemove);
			}

			// add the new shipments
			for (CarrierShipment shipmentToAdd : newShipments) {
				CarrierUtils.addShipment(carrier, shipmentToAdd);
			}

			// counting shipment demand after
			demandAfter = Demand(carrier,demandAfter);


			Gbl.assertIf(demandBefore == demandAfter);
		}


		// output before jsprit run (not necessary)
		new CarrierPlanXmlWriterV2(FreightUtils.getCarriers( scenario )).write( "output/jsprit_unplannedCarriers.xml" ) ;
		// (this will go into the standard "output" directory.  note that this may be removed if this is also used as the configured output dir.)


		// Solving the VRP (generate carrier's tour plans)
		FreightUtils.runJsprit( scenario );

		// Output after jsprit run (not necessary)
		new CarrierPlanXmlWriterV2(FreightUtils.getCarriers( scenario )).write( "output/jsprit_plannedCarriers.xml" ) ;
		// (this will go into the standard "output" directory.  note that this may be removed if this is also used as the configured output dir.)

		// ## MATSim configuration:  ##
		final Controler controler = new Controler( scenario ) ;
		controler.addOverridingModule(new CarrierModule() );

		// otfvis (if you want to use):
//		OTFVisConfigGroup otfVisConfigGroup = ConfigUtils.addOrGetModule( config, OTFVisConfigGroup.class );
//		otfVisConfigGroup.setLinkWidth( 10 );
//		otfVisConfigGroup.setDrawNonMovingItems( false );
//		config.qsim().setTrafficDynamics( QSimConfigGroup.TrafficDynamics.kinematicWaves );
//		config.qsim().setSnapshotStyle( QSimConfigGroup.SnapshotStyle.kinematicWaves );
//		controler.addOverridingModule( new OTFVisLiveModule() );


		// ## Start of the MATSim-Run: ##
		controler.run();

		long after = System.nanoTime();
		double durationMS = (after-before)/1e9;
		System.out.println("Zeit: "+durationMS+" ms");

		//RunFreightAnalysisEventbased Run = new RunFreightAnalysisEventbased("output/freight","analyze");

	}


	//method for building new shipments with the boundary size
	private static void shipmentCreator(int Boundary_value, Carrier carrier, LinkedList<CarrierShipment> newShipments, LinkedList<CarrierShipment> oldShipments) {
		for (CarrierShipment carrierShipment : carrier.getShipments().values()) {

			int rest = carrierShipment.getSize() % Boundary_value;
			int numShipments = carrierShipment.getSize() / Boundary_value;  // number of new shipments to create

			// create a shipment with the remaining shipment goods
			if(numShipments != 0 & rest != 0) {
				CarrierShipment newShipment = createShipment( carrierShipment,1,(int) rest);
				newShipments.add(newShipment); // add the new shipment
			}

			//the new shipments are created
			if(rest != 0) {
				for (int i = 1; i <= numShipments; i++) {
					CarrierShipment newShipment = createShipment(carrierShipment,(i + 1), Boundary_value);
					newShipments.add(newShipment); // add the new shipment in the temporary LinkedList
				}
			}
			else {
				for (int i = 1; i <= numShipments; i++) {
					CarrierShipment newShipment = createShipment(carrierShipment, i, Boundary_value);
					newShipments.add(newShipment); // add the new shipment in the temporary LinkedList
				}
			}

			if(numShipments > 0){
				oldShipments.add(carrierShipment);
			}
		}
	}

	//method for creating new Shipments
	private static CarrierShipment createShipment(CarrierShipment carrierShipment, int id, int Shipment_size){
			if(id == 0){
				CarrierShipment newShipment = CarrierShipment.Builder.newInstance(Id.create(carrierShipment.getId(),CarrierShipment.class),
								carrierShipment.getFrom(), carrierShipment.getTo(), Shipment_size)
						.setDeliveryServiceTime(carrierShipment.getDeliveryServiceTime())
						.setDeliveryTimeWindow(carrierShipment.getDeliveryTimeWindow())
						.setPickupTimeWindow(carrierShipment.getPickupTimeWindow())
						.setPickupServiceTime(carrierShipment.getPickupServiceTime())
						.build();

				return newShipment;
			}
			else {
				CarrierShipment newShipment = CarrierShipment.Builder.newInstance(Id.create(carrierShipment.getId() + "_" + id, CarrierShipment.class),
								carrierShipment.getFrom(), carrierShipment.getTo(), Shipment_size)
						.setDeliveryServiceTime(carrierShipment.getDeliveryServiceTime())
						.setDeliveryTimeWindow(carrierShipment.getDeliveryTimeWindow())
						.setPickupTimeWindow(carrierShipment.getPickupTimeWindow())
						.setPickupServiceTime(carrierShipment.getPickupServiceTime())
						.build();

				return newShipment;
			}

	}

	//method for counting Sizes of all Shipments
	private static int Demand(Carrier carrier, int demand){

		for (CarrierShipment carrierShipment : carrier.getShipments().values()){
			demand = demand + carrierShipment.getSize();
		}
		return demand;
	}

	enum Scenario{
		SCENE_1,
		SCENE_2,
		SCENE_3,
		SCENE_4
	}



	// yyyy I think that having a central freight StrategyManager would be better than the current approach that builds an ad-hoc such
	// strategy manager, with the strategies hardcoded there as well.  I currently see two approaches:

	// (1) freight strategy manager separate from  standard strategy manager.

	// (2) re-use standard strategy manager.

	// Advantage of (2) would be that we could re-use the existing strategy manager infrastructure, including the way it is configured.
	// Disadvantage would be that the "freight" subpopulation (or maybe even freight*) would be an implicitly reserved keyword.  Todos for this path:

	// * remove deprecated methods from StrategyManager so that it becomes shorter; make final; etc. etc.

	// * try to completely remove StrategyManager and replace by GenericStrategyManager.  Since only then will it accept Carriers and CarrierPlans.

	// Note that the freight strategy manager operates on CarrierPlans, which include _all_ tours/drivers.  One could also have drivers optimize
	// individually.  They are, however, not part of the standard population.

	// kai, jan'22


}
