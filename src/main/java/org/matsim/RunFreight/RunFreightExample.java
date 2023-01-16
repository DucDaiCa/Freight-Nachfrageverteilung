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
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freight.FreightConfigGroup;
import org.matsim.contrib.freight.carrier.*;
import org.matsim.contrib.freight.controler.CarrierModule;
import org.matsim.contrib.freight.utils.FreightUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.vehicles.VehicleCapacity;
import org.matsim.vehicles.VehicleType;

import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.Iterator;


/**
 * @see org.matsim.contrib.freight
 */
public class RunFreightExample {

	private static final Logger log = LogManager.getLogger(RunFreightExample.class);

	public static void main(String[] args) throws ExecutionException, InterruptedException{

		// ### config stuff: ###

		Config config = ConfigUtils.loadConfig( IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL( "freight-chessboard-9x9" ), "config.xml" ) );

		config.plans().setInputFile( null ); // remove passenger input

		// more general settings
		config.controler().setOutputDirectory("./output/freight" );

		config.controler().setLastIteration(0 );		// yyyyyy iterations currently do not work; needs to be fixed.  (Internal discussion at end of file.)

		// freight settings
		FreightConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule( config, FreightConfigGroup.class ) ;


		freightConfigGroup.setCarriersFile( "singleCarrierFiveActivitiesWithoutRoutes_Shipments.xml");

		freightConfigGroup.setCarriersVehicleTypesFile( "vehicleTypes.xml");


		// load scenario (this is not loading the freight material):
		Scenario scenario = ScenarioUtils.loadScenario( config );

		// load carriers according to freight config
		FreightUtils.loadCarriersAccordingToFreightConfig( scenario );

		// Write out the original carriers file - before any modification is done
		new CarrierPlanXmlWriterV2(FreightUtils.getCarriers( scenario )).write( "output/originalCarriers.xml" ) ;

		// how to set the capacity of the "light" vehicle type to "1":
		//Ich habe die Kapazität mal auf 20 erhöht, weil er sonst einen Teil der Aufträge nicht fahren kann (Fzg-Kapazität war 5; Aufträge hatten aber auch Größe 7 und 10)
		// Weil wir es unten ja "Demo-halber" verdoppeln, muss als das Fahrzeug wenigstens 20 Einheiten transportieren können.
		FreightUtils.getCarrierVehicleTypes( scenario ).getVehicleTypes().get( Id.create("light", VehicleType.class ) ).getCapacity().setOther( 10 );




		//log.info("Ausgabe der VehicleTypes: "+FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes());

		//int vehicles1 = FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().values().size();
		//log.info("Welche Wagen habe wir: "+vehicles1);


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
				//carrier.getServices().remove(carrierService);
			}
		}*/


		// changing shipment size of existing shipment
		for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
			for (CarrierShipment carrierShipment : carrier.getShipments().values()) {
				int size = carrierShipment.getSize()*3;
				CarrierShipment newShipment = CarrierShipment.Builder.newInstance(carrierShipment.getId(),carrierShipment.getFrom(),carrierShipment.getTo(),size)
						.setDeliveryServiceTime(carrierShipment.getDeliveryServiceTime())
						.setDeliveryTimeWindow(carrierShipment.getDeliveryTimeWindow())
						.setPickupTimeWindow(carrierShipment.getPickupTimeWindow())
						.setPickupServiceTime(carrierShipment.getPickupServiceTime())
						.build();
				CarrierUtils.addShipment(carrier,newShipment); //füge das neue Shipment hinzu
				//carrier.getShipments().remove(carrierShipment); //und lösche das alte heraus
				//log.info("GetTo: "+carrierShipment.getTo()+ "GetFrom: "+carrierShipment.getFrom());
			}
		}

        

		double Boundary_value = FreightUtils.getCarrierVehicleTypes( scenario ).getVehicleTypes().get( Id.create("light", VehicleType.class ) ).getCapacity().getOther();  // Wert der Kapazität der "light" vehicles speichern

		// Test method to create new shipment Test.2
		Collection<Carrier> carrier_Num =  FreightUtils.getCarriers(scenario).getCarriers().values();
		Iterator<Carrier> it = carrier_Num.iterator() ;


		while(it.hasNext()) {
			Carrier carrier = it.next();
			Collection<CarrierShipment> carrierShipment_Num = carrier.getShipments().values();
			Iterator<CarrierShipment>  it1 = carrierShipment_Num.iterator();

			while (it1.hasNext()){
				CarrierShipment carrierShipment = it1.next();
				int size_original =  carrierShipment.getSize();
				int rest = size_original % (int) Boundary_value;
				int size = size_original / (int) Boundary_value;
				log.info("Gib size aus: "+size+" und Value aus: "+Boundary_value);
				for(int i=1; i <= size; i++) {
				log.info("dummyShip:"+ carrierShipment.getId());
																						// Hier soll er neue shipments hinzufügen, aber packt das nur in die alten
					//CarrierShipment newShipment = CarrierShipment.Builder.newInstance(Id.create(carrierShipment.getId()+"_"+i,CarrierShipment.class), carrierShipment.getFrom(), carrierShipment.getTo(),(int) Boundary_value)
					CarrierShipment newShipment = CarrierShipment.Builder.newInstance(carrierShipment.getId(), carrierShipment.getFrom(), carrierShipment.getTo(),(int) Boundary_value)
							.setDeliveryServiceTime(carrierShipment.getDeliveryServiceTime())
							.setDeliveryTimeWindow(carrierShipment.getDeliveryTimeWindow()).setPickupTimeWindow(carrierShipment.getPickupTimeWindow())
							.setPickupServiceTime(carrierShipment.getPickupServiceTime())
							.build();

					log.info("Sehe ich das hier?");
					CarrierUtils.addShipment(carrier, newShipment); //füge das neue Shipment hinzu
					//carrier.getShipments().remove(carrierShipment); //und lösche das alte heraus
				}
			log.info("Wie oft gehen wir hier durch");
			}
		}


		//		// Test method to create new shipment Test.2 Test.1
		/*for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
			for (CarrierShipment carrierShipment : carrier.getShipments().values()) {
				//log.info("CarrierShipments ausgeben: " +carrierShipment+ " Carrier ausgeben: "+ carrier);
				CarrierShipment dummyShipment = carrierShipment; // für die Schleife, wenn shipment nach Halbierung immernoch größer ist als Vehicle Kapazität
				//log.info("Gib Dummy aus(vorher):  "+dummyShipment);

				int  Size_original = dummyShipment.getSize();

				int rest = Size_original % (int) Boundary_value;
				int size = Size_original / (int) Boundary_value;
				log.info("Gib Size Original: "+Size_original+" und Boundary value aus: "+Boundary_value);

				for(int i=1; i <= size; i++) {
					String Shipment_Id = String.valueOf(i);
						log.info("HALLOO");
					//	CarrierShipment newShipment = CarrierShipment.Builder.newInstance(carrierShipment.getId(), carrierShipment.getFrom(), carrierShipment.getTo(), size)
						CarrierShipment newShipment = CarrierShipment.Builder.newInstance(Id.create(carrierShipment.getId()+"_"+i,CarrierShipment.class), carrierShipment.getFrom(), carrierShipment.getTo(),(int) Boundary_value)
								.setDeliveryServiceTime(carrierShipment.getDeliveryServiceTime())
								.setDeliveryTimeWindow(carrierShipment.getDeliveryTimeWindow())
								.setPickupTimeWindow(carrierShipment.getPickupTimeWindow())
								.setPickupServiceTime(carrierShipment.getPickupServiceTime())
								.build();

						dummyShipment = newShipment;
						//log.info("Gib Dummy aus(nachher):  "+dummyShipment);
						CarrierUtils.addShipment(carrier, newShipment); //füge das neue Shipment hinzu
						//carrier.getShipments().remove(carrierShipment); //und lösche das alte heraus



				}
				log.info("Rest ausgabe: " + rest + " und Size ausgabe: " + size);
			}
		}*/








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
