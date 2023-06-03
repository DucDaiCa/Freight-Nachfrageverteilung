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
import org.matsim.RunFreightAnalysis.RunFreightAnalysisEventbased;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freight.FreightConfigGroup;
import org.matsim.contrib.freight.carrier.*;
import org.matsim.contrib.freight.controler.CarrierModule;
import org.matsim.contrib.freight.controler.CarrierScoringFunctionFactory;
import org.matsim.contrib.freight.controler.FreightUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.VehicleType;

import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.ExecutionException;


public class RunFreightExample {

	private static final Logger log = LogManager.getLogger(RunFreightExample.class);

	public static void main(String[] args) throws ExecutionException, InterruptedException, IOException {
		long start = System.nanoTime();
		// ### config stuff: ###
		Config config = createConfig();

		// load scenario (this is not loading the freight material):
		org.matsim.api.core.v01.Scenario scenario = ScenarioUtils.loadScenario( config );

		// load carriers according to freight config
		FreightUtils.loadCarriersAccordingToFreightConfig( scenario );

		// Write out the original carriers file - before any modification is done
		new CarrierPlanWriter(FreightUtils.getCarriers( scenario )).write( "output/originalCarriers.xml" ) ;

		// how to set the capacity of the "light" vehicle type to "25":
		//FreightUtils.getCarrierVehicleTypes( scenario ).getVehicleTypes().get( Id.create("light", VehicleType.class ) ).getCapacity().setOther( 25 );

		// What vehicle types do we have
		for(VehicleType vehicleType : FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().values()) {
			log.info(vehicleType.getId()+": "+vehicleType.getCapacity().getOther());
		}


		//Hier geschieht der Hauptteil der Arbeit: Das Aufteilen der Shipments :)
		changeShipmentSize(scenario);


		// output before jsprit run (not necessary)
		new CarrierPlanWriter(FreightUtils.getCarriers( scenario )).write( "output/jsprit_unplannedCarriers.xml" ) ;
		// (this will go into the standard "output" directory.  note that this may be removed if this is also used as the configured output dir.)


		// Solving the VRP (generate carrier's tour plans)
		FreightUtils.runJsprit( scenario );

		// Output after jsprit run (not necessary)
		new CarrierPlanWriter(FreightUtils.getCarriers( scenario )).write( "output/jsprit_plannedCarriers.xml" ) ;
		// (this will go into the standard "output" directory.  note that this may be removed if this is also used as the configured output dir.)

		// ## MATSim configuration:  ##
		final Controler controler = new Controler( scenario ) ;
		controler.addOverridingModule(new CarrierModule() );
		controler.addOverridingModule(new AbstractModule() {
										  @Override
										  public void install() {
											  final MyEventBasedCarrierScorer carrierScorer = new MyEventBasedCarrierScorer();

											  bind(CarrierScoringFunctionFactory.class).toInstance(carrierScorer);
										  }
									  });



		// ## Start of the MATSim-Run: ##
		controler.run();

		long end = System.nanoTime();
		double durationMS = (end-start)/1e9;
		System.out.println("Zeit: "+durationMS+" ms");

		RunFreightAnalysisEventbased FreightAnalysis = new RunFreightAnalysisEventbased(controler.getControlerIO().getOutputPath(),controler.getControlerIO().getOutputPath()+"/analyze");
		try {
			FreightAnalysis.runAnalysis();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

	}

	private static Config createConfig() {
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
		return config;
	}

	private static void changeShipmentSize(org.matsim.api.core.v01.Scenario scenario) {

		// changing shipment size of existing shipment
		for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
			CarrierUtils.setJspritIterations(carrier,5);
			/*for (CarrierShipment carrierShipment : carrier.getShipments().values()) {
				int size = carrierShipment.getSize()*3;

				CarrierShipment newShipment = createShipment(carrierShipment, 0, size);
				CarrierUtils.addShipment(carrier,newShipment); //add the new shipment to the carrier
			}*/
		}

		Plot mySelection =  Plot.OneHalfOfSmallestSize;
		double Boundary_value = Double.MAX_VALUE;
		for(VehicleType vehicleType : FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().values()){
			if(vehicleType.getCapacity().getOther() < Boundary_value)
			{
				Boundary_value = vehicleType.getCapacity().getOther();
			}
		}

		switch (mySelection) {
			case SmallestSize:
			{
				//size of the smallest vehicle to break up the shipment into smaller chunks
			}

			break;
			case OneHalfOfSmallestSize:
			{
				Boundary_value = Boundary_value/2;
			}
			break;
			case ThirdOfSmallestSize:
			{
				Boundary_value = Boundary_value/3;
			}
			break;
			case AverageOfTwoSmallestSize:
			{
				double smallestSize = Double.MAX_VALUE;
				double secsmallestSize = Double.MAX_VALUE;

				for(VehicleType vehicleType : FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().values()) {
					if(vehicleType.getCapacity().getOther() < smallestSize) {
						if (smallestSize < secsmallestSize) {
							secsmallestSize = smallestSize;
						}
						smallestSize = vehicleType.getCapacity().getOther();
					}
					else {
						if (vehicleType.getCapacity().getOther() < secsmallestSize) {
							secsmallestSize = vehicleType.getCapacity().getOther();
						}
					}

				}

				Boundary_value = (smallestSize+secsmallestSize)/2;
				break;
			}
			default:
				throw new IllegalStateException("Unexpected value: " + mySelection);
		}

		// method to create new shipments
		for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {

			LinkedList<CarrierShipment> newShipments = new LinkedList<>();  // Liste um die "neuen" Shipments temporär zu speichern, weil man sie nicht während des Iterierens hinzufügen kann.
			LinkedList<CarrierShipment> oldShipments = new LinkedList<>(); // Liste um die "alten" Shipments temporär zu speichern
			int demandBefore = 0;
			int demandAfter = 0;
			double deliveryServiceTimeBefore = 0.0;
			double deliveryServiceTimeAfter = 0.0;
			double pickupServiceTimeBefore = 0.0;
			double pickupServiceTimeAfter = 0.0;

			// counting shipment demand, deliveryServiceTime, pickupServiceTime before making new shipments
			demandBefore = Demand(carrier,demandBefore);
			deliveryServiceTimeBefore = SumServiceTime(carrier,deliveryServiceTimeBefore,1);
			pickupServiceTimeBefore= SumServiceTime(carrier,pickupServiceTimeBefore,2);


			shipmentCreator((int) Boundary_value, carrier, newShipments, oldShipments);

			// remove the old shipments
			for (CarrierShipment shipmentToRemove : oldShipments) {
				carrier.getShipments().remove(shipmentToRemove.getId(), shipmentToRemove);
			}

			// add the new shipments
			for (CarrierShipment shipmentToAdd : newShipments) {
				CarrierUtils.addShipment(carrier, shipmentToAdd);
			}

			// counting shipment demand, deliveryServiceTime, pickupServiceTime after making the new shipments
			demandAfter = Demand(carrier,demandAfter);
			deliveryServiceTimeAfter = SumServiceTime(carrier,deliveryServiceTimeAfter,1);
			pickupServiceTimeAfter = SumServiceTime(carrier,pickupServiceTimeAfter,2);

			// here checking if the numbers stayed the same
			Gbl.assertIf(demandBefore == demandAfter);
			Gbl.assertIf(deliveryServiceTimeBefore == deliveryServiceTimeAfter);
			Gbl.assertIf(pickupServiceTimeBefore == pickupServiceTimeAfter);
		}
	}

	/**
	 * method for building new shipment with the new size
	 *
	 * @param Boundary_value the "new" size for the shipments
	 * @param carrier the already existing carrier
	 * @param newShipments the temporary list for the new Shipments
	 * @param oldShipments the temporary list for the old Shipments
	 */
	private static void shipmentCreator(int Boundary_value, Carrier carrier, LinkedList<CarrierShipment> newShipments, LinkedList<CarrierShipment> oldShipments) {
		for (CarrierShipment carrierShipment : carrier.getShipments().values()) {

			int rest = carrierShipment.getSize() % Boundary_value;
			int numShipments = carrierShipment.getSize() / Boundary_value;  // number of new shipments to create

			// create a shipment with the remaining shipment goods
			if(numShipments != 0 & rest != 0) {
				CarrierShipment newShipment = createShipment( carrierShipment,1, rest);
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

	/**
	 * method for creating new Shipments
	 *
	 * @param carrierShipment the shipment
	 * @param id id of the shipment
	 * @param shipmentSize new size for the shipment
	 * @return  the new/modified CarrierShipment
	 */
	private static CarrierShipment createShipment(CarrierShipment carrierShipment, int id, int shipmentSize){
			if(id == 0){
				return CarrierShipment.Builder.newInstance(Id.create(carrierShipment.getId(),CarrierShipment.class),
								carrierShipment.getFrom(), carrierShipment.getTo(), shipmentSize)
						.setDeliveryServiceTime((double) shipmentSize*180)
						.setDeliveryTimeWindow(carrierShipment.getDeliveryTimeWindow())
						.setPickupTimeWindow(carrierShipment.getPickupTimeWindow())
						.setPickupServiceTime((double) shipmentSize*180)
						.build();
			}
			else {
				return CarrierShipment.Builder.newInstance(Id.create(carrierShipment.getId() + "_" + id, CarrierShipment.class),
								carrierShipment.getFrom(), carrierShipment.getTo(), shipmentSize)
						.setDeliveryServiceTime((double) shipmentSize*180)
						.setDeliveryTimeWindow(carrierShipment.getDeliveryTimeWindow())
						.setPickupTimeWindow(carrierShipment.getPickupTimeWindow())
						.setPickupServiceTime((double) shipmentSize*180)
						.build();
			}

	}

	/**
	 * method for counting sizes/demand of all Shipments
	 *
	 * @param carrier the already existing carrier
	 * @param demand counter to sum up all shipments
	 * @return the sum of the whole shipments
	 */
	private static int Demand(Carrier carrier, int demand){

		for (CarrierShipment carrierShipment : carrier.getShipments().values()){
			demand = demand + carrierShipment.getSize();
		}
		return demand;
	}

	private static double SumServiceTime(Carrier carrier, double serviceTime, int num ){

		switch(num) {
			case 1:{
				for (CarrierShipment carrierShipment : carrier.getShipments().values()) {
					serviceTime = serviceTime + carrierShipment.getDeliveryServiceTime();
				}
			}
			break;
			case 2:{
				for (CarrierShipment carrierShipment : carrier.getShipments().values()) {
					serviceTime = serviceTime + carrierShipment.getPickupServiceTime();
				}
			}
			break;
		}

		return serviceTime;
	}

	enum Plot {
		SmallestSize,
		OneHalfOfSmallestSize,
		ThirdOfSmallestSize,
		AverageOfTwoSmallestSize
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
