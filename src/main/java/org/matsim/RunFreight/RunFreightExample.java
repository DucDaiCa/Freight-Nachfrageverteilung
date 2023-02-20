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
import org.matsim.core.gbl.Gbl;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.vehicles.VehicleCapacity;
import org.matsim.vehicles.VehicleType;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
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
		FreightUtils.getCarrierVehicleTypes( scenario ).getVehicleTypes().get( Id.create("light", VehicleType.class ) ).getCapacity().setOther( 25 );

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
			for (CarrierShipment carrierShipment : carrier.getShipments().values()) {
				int size = carrierShipment.getSize()*4;

				CarrierShipment newShipment = CreateShipment(carrierShipment, 0, size);
				CarrierUtils.addShipment(carrier,newShipment); //füge das neue Shipment hinzu
			}
		}

		// Todo (KMT 2/2/23): Müsste das nicht auch in jedem Carrier unabhängig geschaut werden?
		// Todo (KMT 2/2/23): Mittelfristig vermutlich sogar sowas wie: 1) Gehe je Carrier durch all vehTypes durch, sammle 2) die Größe ein, merke dir (die kleinste??) und bilde davon dann deinen Grenzwert...
		// double Boundary_value = FreightUtils.getCarrierVehicleTypes( scenario ).getVehicleTypes().get( Id.create("light", VehicleType.class ) ).getCapacity().getOther();  // Wert der Kapazität der "light" vehicles speichern
		double Boundary_value = 1000.0;
		for(VehicleType vehicleType : FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().values()){
			if(vehicleType.getCapacity().getOther() < Boundary_value)
			{
							Boundary_value = vehicleType.getCapacity().getOther();
			}
		}


		//Todo: (KMT 2/2/23): Hier de facto 2. for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values())  Schleife in Folge --> Zusammenfassen?
		// Allgemein: Hier mal "aufräumen" ;)
		// method to create new shipments
		for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {

			LinkedList<CarrierShipment> newShipments = new LinkedList<>();  // Liste um die "neuen" Shipments temporär zu speichern, weil man sie nicht während des Iterierens hinzufügen kann.
			LinkedList<CarrierShipment> oldShipments = new LinkedList<>(); // Liste um die "alten" Shipments temporär zu speichern
			int demandBefore = 0;
			int demandAfter = 0;

			// counting shipment demand before
			for (CarrierShipment carrierShipment : carrier.getShipments().values()){
				demandBefore = demandBefore + carrierShipment.getSize();
			}

			for (CarrierShipment carrierShipment : carrier.getShipments().values()) {

				int rest = carrierShipment.getSize() % (int) Boundary_value; //TODO (KMT) Es muss am Ende auch noch ein Shipment mit dem Rest erstellt werden
				//TODO: (KMT 2/2/23) Bist du dir sicher, dass hier dann eine abgerundete Zahl raus kommt? ((Ich bin es aus dem Kopf nicht)
				// Oder kann passieren, dass er bei z.B. 9/5 dann 1.8 raus bekommt und es dann 2 wird? (was dann ja mit dem Rest (4) zu einer Nachfrage von 2*5+4=14 führen würde
				int numShipments = carrierShipment.getSize() / (int) Boundary_value;  // number of new shipments to create

				// create a shipment with the remaining shipment goods
				if(numShipments != 0 & rest != 0) {
					CarrierShipment newShipment = CreateShipment( carrierShipment,1,(int) rest);
					newShipments.add(newShipment); // add the new shipment
				}

				//the new shipments are created
				if(rest != 0) {
					for (int i = 1; i <= numShipments; i++) {
						CarrierShipment newShipment = CreateShipment(carrierShipment,(i + 1), (int) Boundary_value);
						newShipments.add(newShipment); // add the new shipment in the temporary LinkedList
					}
				}
				else {
					for (int i = 1; i <= numShipments; i++) {
						CarrierShipment newShipment = CreateShipment(carrierShipment, i, (int) Boundary_value);
						newShipments.add(newShipment); // add the new shipment in the temporary LinkedList
					}
				}

				if(numShipments > 0){
					oldShipments.add(carrierShipment);
				}
			}

			// remove the old shipments
			for (CarrierShipment shipmentToRemove : oldShipments) {
				carrier.getShipments().remove(shipmentToRemove.getId(), shipmentToRemove);
			}

			// add the new shipments
			for (CarrierShipment shipmentToAdd : newShipments) {
				CarrierUtils.addShipment(carrier, shipmentToAdd);
			}

			// counting shipment demand after
			for (CarrierShipment carrierShipment : carrier.getShipments().values()){
				demandAfter = demandAfter + carrierShipment.getSize();
			}

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
	}

	// Erstellung eines neuen Shipments
	public static CarrierShipment CreateShipment(CarrierShipment carrierShipment,  int id, int Shipment_size){
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
