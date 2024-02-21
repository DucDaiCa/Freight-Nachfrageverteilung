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
import org.matsim.api.core.v01.Scenario;
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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import java.io.File;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class RunFreightExample {

	private static final Logger log = LogManager.getLogger(RunFreightExample.class);

	private static int nuOfJspritIteration;

	public static void main(String[] args) throws ExecutionException, InterruptedException, IOException {

		for (String arg : args) {
			log.info( arg );
		}

		if ( args.length==0 ) {
			String inputPath = "./input/";
			args = new String[] {
					inputPath+ "TestScenario_SingleVehicle_FourShipments_Ver.A.xml",
					inputPath + "VehicleTypes_26t_size24.xml",
					"800",                                                    //only for demonstration.
					"./output/Demo_Freight",
			};
		}

		// extending xml name with the iteration count (2)
		xmlNameChangeID(args);


		// ### config stuff: ###
		Config config = prepareConfig(args);

		// load scenario (this is not loading the freight material):
		org.matsim.api.core.v01.Scenario scenario = ScenarioUtils.loadScenario( config );


		// load carriers according to freight config
		FreightUtils.loadCarriersAccordingToFreightConfig( scenario );

		// changes Shipment sizes randomly (1) then comment code and uncomment (2) above
		// randomShipmentDistribution(scenario,args);


		// set # of jsprit iterations
		for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
			log.warn("Overwriting the number of jsprit iterations for carrier: " + carrier.getId() + ". Value was before " +CarrierUtils.getJspritIterations(carrier) + "and is now " + nuOfJspritIteration);
			CarrierUtils.setJspritIterations(carrier, nuOfJspritIteration);
		}

		// Write out the original carriers file - before any modification is done
		new CarrierPlanWriter(FreightUtils.getCarriers( scenario )).write( "output/originalCarriers.xml" ) ;

		// how to set the capacity of the "light" vehicle type to "25":
		//FreightUtils.getCarrierVehicleTypes( scenario ).getVehicleTypes().get( Id.create("light", VehicleType.class ) ).getCapacity().setOther( 25 );

		// What vehicle types do we have
		//for(VehicleType vehicleType : FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().values()) {
		//	log.info(vehicleType.getId()+": "+vehicleType.getCapacity().getOther());
		//}

		//Hier geschieht der Hauptteil der Arbeit: Das Aufteilen der Shipments :)
		changeShipmentSize(scenario);


		// output before jsprit run (not necessary)
		new CarrierPlanWriter(FreightUtils.getCarriers( scenario )).write( "output/jsprit_unplannedCarriers.xml" ) ;
		// (this will go into the standard "output" directory.  note that this may be removed if this is also used as the configured output dir.)

		// creating a list and arrange shipment size of the tour
		shipmentSizeNumerator(scenario);

		//count the runtime of Jsprit and MATSim
		long start = System.nanoTime();

		// Solving the VRP (generate carrier's tour plans)
		FreightUtils.runJsprit( scenario );

		long end = System.nanoTime();
		double durationSec = (end-start)/1e9;
		double durationMin = (end-start)/(1e9*60);
		//System.out.println("Zeit: "+durationMS+" s");


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

		// Creating the Analysis files
		RunFreightAnalysisEventbased FreightAnalysis = new RunFreightAnalysisEventbased(controler.getControlerIO().getOutputPath(),controler.getControlerIO().getOutputPath()+"/analyze");
		try {
			FreightAnalysis.runAnalysis();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// Output after jsprit run (not necessary)
		new CarrierPlanWriter(FreightUtils.getCarriers( scenario )).write( controler.getControlerIO().getOutputPath()+"/analyze/jsprit_plannedCarriers.xml" ) ;
		// (this will go into the standard "output" directory.  note that this may be removed if this is also used as the configured output dir.)

		//Output the number of destination approaches of the tour
		tourDestinationCounter(controler.getControlerIO().getOutputPath());

		runTimeOutput(durationSec, durationMin, controler);
	}

	/**
	 * method for distributing Shipments randomly to given locations
	 *
	 * @param args String array with arguments
	 * @param scenario
	 */
	private static void randomShipmentDistribution(Scenario scenario, String[] args) {
		int demand = 0;
		Random random = new Random();
		Map<String, Integer> countDestination = new HashMap<>();

		for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()){
			demand  = demand(carrier,demand);

			for (CarrierShipment carrierShipment : carrier.getShipments().values()) {

				if(!countDestination.containsKey(carrierShipment.getTo().toString()));
				{
					countDestination.put(carrierShipment.getTo().toString(),0);
				}
			}

			demand = demand - countDestination.size();
			int numOfDestination = countDestination.size()-1;

			// random distribution on the HashMap entries
			for (Map.Entry<String, Integer> entry : countDestination.entrySet()) {
				int randomValue = random.nextInt(demand + 1); // Zufällige Zahl von 0 bis total

				if (numOfDestination != 0) {
					//randomValue =23;
					countDestination.put(entry.getKey(), randomValue+1);

					demand -= randomValue;
					//log.info("Gebe mir neuen demand aus: "+demand);
					numOfDestination -= 1;
					//log.info("Gebe mir neuen destinationlänge aus: "+numOfDestination);

				} else {
					countDestination.put(entry.getKey(), demand+1);
				}
			}

		}
		//log.info("Gebe mir die HashMap der Zielorte aus: "+countDestination);
		for (var carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
			for (var carrierShipment : carrier.getShipments().values()) {
				for (Map.Entry<String, Integer> entry : countDestination.entrySet()) {
					if(entry.getKey().equals(carrierShipment.getTo().toString())) {

						CarrierShipment newCarrierShipment = CarrierShipment.Builder.newInstance(Id.create(carrierShipment.getId(),CarrierShipment.class),
										carrierShipment.getFrom(), carrierShipment.getTo(), entry.getValue())
								.setDeliveryServiceTime((double) entry.getValue()*180)
								.setDeliveryTimeWindow(carrierShipment.getDeliveryTimeWindow())
								.setPickupTimeWindow(carrierShipment.getPickupTimeWindow())
								.setPickupServiceTime((double) entry.getValue()*180)
								.build();
						CarrierUtils.addShipment(carrier,newCarrierShipment);
					}
				}
			}
		}
		new CarrierPlanWriter(FreightUtils.getCarriers( scenario )).write(args[0]) ;
	}

	/**
	 * renames the ID names of a xml file
	 *
	 * @param args String array with arguments
	 */
	private static void xmlNameChangeID(String[] args) {
		try {
			//loading XML-document
			File inputFile = new File(args[0]);
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(inputFile);

			// getting the root of the document
			Element root = doc.getDocumentElement();

			// getting all carrier elements
			NodeList carrierList = root.getElementsByTagName("carrier");

			for (int i = 0; i < carrierList.getLength(); i++) {
				Element carrier = (Element) carrierList.item(i);

				// changing carrier ID
				String currentCarrierID = carrier.getAttribute("id");
				String newCarrierID = currentCarrierID + "_" + args[2] + "it";
				carrier.setAttribute("id", newCarrierID);

				// getting all vehicle elements int the carrier
				NodeList vehicleList = carrier.getElementsByTagName("vehicle");


				for (int j = 0; j < vehicleList.getLength(); j++) {
					Element vehicle = (Element) vehicleList.item(j);

					// changing the vehicle id
					String currentVehicleID = vehicle.getAttribute("id");
					String newVehicleID = currentVehicleID + "_" + args[2] + "it";
					vehicle.setAttribute("id", newVehicleID);
				}
			}

			// saving the updated document in a xml file
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = transformerFactory.newTransformer();
			DOMSource source = new DOMSource(doc);
			StreamResult result = new StreamResult(new File("input/Shipment.xml"));
			transformer.transform(source, result);
			args[0] = "input/Shipment.xml";

			log.info("XML wurde erfolgreich aktualisiert: "+ args[0] );

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * writing the runtime in a txt file
	 *
	 * @param durationSec time in seconds
	 * @param durationMin time in minutes
	 */
	private static void runTimeOutput(double durationSec, double durationMin, Controler controler) {
		File runTimeFile = new File(controler.getControlerIO().getOutputPath()+"/analyze/runTimeFile.txt");
		BufferedWriter buffer = null;
		try {

			// create new BufferedWriter for the output file
			buffer = new BufferedWriter(new FileWriter(runTimeFile));


			// put key and value separated by a tab
			buffer.write("Die Laufzeit des Szenarios beträgt: "+ durationSec +" s \t  "+ durationMin +" min");

			// new line
			buffer.newLine();


			buffer.flush();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {

			try {

				// close the writer
				buffer.close();
			} catch (Exception e) {
			}
		}
	}

	/**
	 * counts how often a destination was driven to as part of a tour
	 *
	 * @param outpath path of the folder
	 */
	private static void tourDestinationCounter(String outpath) {
		try {
			File inputFile = new File(outpath+"/analyze/jsprit_plannedCarriers.xml");
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document doc = dBuilder.parse(inputFile);
			doc.getDocumentElement().normalize();

			NodeList tourList = doc.getElementsByTagName("tour");
			int tourCount = tourList.getLength();

			// HashMap to store destination and its count
			HashMap<String, Integer> destinationCountMap = new HashMap<>();

			// new file object
			File file = new File(outpath+"/analyze/TourDestination_Carrier.txt");

			for (int i = 0; i < tourCount; i++) {
				Node tourNode = tourList.item(i);

				if (tourNode.getNodeType() == Node.ELEMENT_NODE) {
					HashMap<String, Integer>  counter = new HashMap<>(); // so that he won't count the same destination more than once for a tour
					nullHashMapBuilder(doc,counter);
					HashMap<String, Integer> tourDestinationCounter = new HashMap<>(); //counting  right amount of shipments destinations for the every tour
					Element tourElement = (Element) tourNode;
					NodeList actList = tourElement.getElementsByTagName("act");
					int actCount = actList.getLength();

					for (int j = 0; j < actCount; j++) {
						Node actNode = actList.item(j);

						if (actNode.getNodeType() == Node.ELEMENT_NODE) {
							Element actElement = (Element) actNode;
							String actType = actElement.getAttribute("type");

							if (actType.equals("delivery")) {
								String shipmentId = actElement.getAttribute("shipmentId");
								String destination = getDestinationFromShipmentId(doc, shipmentId);

								// Update the count for the destination
								if (destinationCountMap.containsKey(destination) ) {

									if(counter.get(destination)== 0) {
										int count = destinationCountMap.get(destination);
										destinationCountMap.put(destination, count + 1);
										counter.put(destination, 1);
									}
								} else {
									destinationCountMap.put(destination, 1);
									counter.put(destination, 1);
								}

								if ( tourDestinationCounter.containsKey(destination) ) {

									int counte = tourDestinationCounter.get(destination);
									tourDestinationCounter.put(destination, counte + 1);

								} else{
									tourDestinationCounter.put(destination, 1);
								}

							}
						}
					}
					System.out.println("Zielorte der Fahrt: "+tourDestinationCounter);
					//OutputTourDestination(tourDestinationCounter,1,file);
				}
			}

			// Prints the destinations and their counts
			outputTourDestination(destinationCountMap,2,file);
			for (Map.Entry<String, Integer> entries : destinationCountMap.entrySet()) {
				System.out.println("Zielort: " + entries.getKey() + "  \t   Wie oft hingefahren: " + entries.getValue());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * writes the destination and their counts on a file
	 *
	 * @param map HashMap with the information
	 */
	private static void outputTourDestination(HashMap<String,Integer> map, int num, File file ){

		BufferedWriter bf = null;
		switch(num) {
			case 1: {
				try {

					// create new BufferedWriter for the output file
					bf = new BufferedWriter(new FileWriter(file));
					// iterate map entries
					for (Map.Entry<String, Integer> entry :
							map.entrySet()) {

						// put key and value separated by a colon
						bf.write("Zielorte der Fahrt: "+map);

						// new line
						bf.newLine();
					}

					bf.flush();
				} catch (IOException e) {
					e.printStackTrace();
				} finally {

					try {

						// close the writer
						bf.close();
					} catch (Exception e) {
					}
				}
			}
			break;
			case 2:{
				try {

					// create new BufferedWriter for the output file
					bf = new BufferedWriter(new FileWriter(file));

					// iterate map entries
					for (Map.Entry<String, Integer> entry :
							map.entrySet()) {

						// put key and value separated by a colon
						bf.write("Zielort: "+entry.getKey() + "  \t   Wie oft hingefahren: " + entry.getValue());

						// new line
						bf.newLine();
					}

					bf.flush();
				} catch (IOException e) {
					e.printStackTrace();
				} finally {

					try {

						// close the writer
						bf.close();
					} catch (Exception e) {
					}
				}
			}
			break;
		}
	}

	/**
	 * method to output the shipment sizes in a HashMap
	 *
	 */
	private static void shipmentSizeNumerator(Scenario scenario) {
		LinkedList<Integer> shipmentSizes = new LinkedList<>();
		for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
			for (CarrierShipment carrierShipment : carrier.getShipments().values()) {
				shipmentSizes.add(carrierShipment.getSize());
			}
		}
		Collections.sort(shipmentSizes);
		//log.info("Gib mir die Größe von dem Shipments aus: " + shipmentSizes);

		Map<Integer, Integer> countMap = countOccurrences(shipmentSizes);

		// sort by key
		Map<Integer, Integer> sortCountMap = new TreeMap<Integer, Integer>(countMap);

		// Output shipment size occur
		//log.info(sortCountMap.entrySet());
		/*for (Map.Entry<Integer, Integer> entry : countMap.entrySet()) {
			System.out.println("Number " + entry.getKey() + " occurs " + entry.getValue() + " times.");
		}*/
	}

		public static Map<Integer, Integer> countOccurrences(LinkedList<Integer> linkedList) {
			Map<Integer, Integer> countMap = new HashMap<>();

			for (Integer number : linkedList) {
				countMap.put(number, countMap.getOrDefault(number, 0) + 1);
			}

			return countMap;
		}

		private static void nullHashMapBuilder(Document doc,HashMap hashMap){
		NodeList shipmentList = doc.getElementsByTagName("shipment");

			for (int temp = 0; temp < shipmentList.getLength(); temp++) {
				Node node = shipmentList.item(temp);
				if (node.getNodeType() == Node.ELEMENT_NODE) {
					Element shipmentElement = (Element) node;
					String destination = shipmentElement.getAttribute("to");
					hashMap.put(destination, hashMap.getOrDefault(destination, 0));
				}
			}
		}


	private static Config prepareConfig(String[] args) {
		Config config = ConfigUtils.createConfig();

		config.network().setInputFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/berlin/berlin-v5.5-10pct/input/berlin-v5.5-network.xml.gz");
		// more general settings
		config.controler().setOutputDirectory(args[3]);
		config.controler().setLastIteration(0 );		// yyyyyy iterations currently do not work; needs to be fixed.  (Internal discussion at end of file.)
		config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		// freight settings
		FreightConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule( config, FreightConfigGroup.class ) ;
		freightConfigGroup.setCarriersFile(args[0]);
		freightConfigGroup.setCarriersVehicleTypesFile(args[1]);

		nuOfJspritIteration = Integer.parseInt(args[2]);
		return config;
	}

//ZUI
	/**
	 * method for breaking up Shipments into smaller ones
	 */
	private static void changeShipmentSize(org.matsim.api.core.v01.Scenario scenario) {

		/*// changing shipment size of existing shipment
		for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
			CarrierUtils.setJspritIterations(carrier,5);
			 *//*
			for (CarrierShipment carrierShipment : carrier.getShipments().values()) {
				int size = carrierShipment.getSize()*2;

				CarrierShipment newShipment = createShipment(carrierShipment, 0, size);
				CarrierUtils.addShipment(carrier,newShipment); //add the new shipment to the carrier
			}
			 *//*
		}*/

		Plot mySelection =  Plot.SizeOne;
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
			case SizeEight:
			{
				Boundary_value = 8;
			}
			break;
			case SizeFour:
			{
				Boundary_value = 4;
			}
			break;
			case SizeTwo:
			{
				Boundary_value = 2;
			}
			break;
			case SizeOne:
			{
				Boundary_value = 1;
			}
			break;
			default:
				throw new IllegalStateException("Unexpected value: " + mySelection);
		}

		// method to create new shipments ( hier werden die Shipments aufgeteilt und neu erstellt )
		createShipment(scenario, (int) Boundary_value);

	}

	private static void createShipment(Scenario scenario, int Boundary_value) {
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
			demandBefore = demand(carrier,demandBefore);
			deliveryServiceTimeBefore = sumServiceTime(carrier,deliveryServiceTimeBefore,1);
			pickupServiceTimeBefore= sumServiceTime(carrier,pickupServiceTimeBefore,2);


			shipmentCreator(Boundary_value, carrier, newShipments, oldShipments);

			// remove the old shipments
			for (CarrierShipment shipmentToRemove : oldShipments) {
				carrier.getShipments().remove(shipmentToRemove.getId(), shipmentToRemove);
			}

			// add the new shipments
			for (CarrierShipment shipmentToAdd : newShipments) {
				CarrierUtils.addShipment(carrier, shipmentToAdd);
			}

			// counting shipment demand, deliveryServiceTime, pickupServiceTime after making the new shipments
			demandAfter = demand(carrier,demandAfter);
			deliveryServiceTimeAfter = sumServiceTime(carrier,deliveryServiceTimeAfter,1);
			pickupServiceTimeAfter = sumServiceTime(carrier,pickupServiceTimeAfter,2);

			// here checking if the numbers stayed the same
			Gbl.assertIf(demandBefore == demandAfter);
			Gbl.assertIf(deliveryServiceTimeBefore == deliveryServiceTimeAfter);
			Gbl.assertIf(pickupServiceTimeBefore == pickupServiceTimeAfter);
		}
	}

	/**
	 * method for building new shipment with the new shipmenntsize
	 *
	 * @param size the "new" size for the shipments
	 * @param carrier the already existing carrier
	 * @param newShipments the temporary list for the new Shipments
	 * @param oldShipments the temporary list for the old Shipments
	 */
	private static void shipmentCreator(int size, Carrier carrier, LinkedList<CarrierShipment> newShipments, LinkedList<CarrierShipment> oldShipments) {
		for (CarrierShipment carrierShipment : carrier.getShipments().values()) {

			int rest = carrierShipment.getSize() % size;
			int numShipments = carrierShipment.getSize() / size;  // number of new shipments to create

			// create a shipment with the remaining shipment goods
			if(numShipments != 0 & rest != 0) {
				CarrierShipment newShipment = createShipment( carrierShipment,1, rest);
				newShipments.add(newShipment); // add the new shipment
			}

			//the new shipments are created
			if(rest != 0) {
				for (int i = 1; i <= numShipments; i++) {
					CarrierShipment newShipment = createShipment(carrierShipment,(i + 1), size);
					newShipments.add(newShipment); // add the new shipment in the temporary LinkedList
				}
			}
			else {
				for (int i = 1; i <= numShipments; i++) {
					CarrierShipment newShipment = createShipment(carrierShipment, i, size);
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
	private static int demand(Carrier carrier, int demand){

		for (CarrierShipment carrierShipment : carrier.getShipments().values()){
			demand = demand + carrierShipment.getSize();
		}
		return demand;
	}

	/**
	 * method for counting sizes/demand of all Shipments
	 *
	 * @param carrier the already existing carrier
	 * @param serviceTime variable to sum up servicetime in the carrier
	 * @param num variable to decide if sum up delivery or pickup service time
	 * @return the sum of the whole shipments
	 */
	private static double sumServiceTime(Carrier carrier, double serviceTime, int num ){

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
		SizeEight,
		SizeFour,
		SizeTwo,
		SizeOne,


	}

	/**
	 * method for counting sizes/demand of all Shipments
	 *
	 * @param doc document file of an xml file
	 * @param shipmentId  ID of the shipment
	 * @return return the destination of the shipment
	 */
	private static String getDestinationFromShipmentId(Document doc, String shipmentId) {
		String destination = "";
		NodeList shipmentList = doc.getElementsByTagName("shipment");

		for (int i = 0; i < shipmentList.getLength(); i++) {
			Node shipmentNode = shipmentList.item(i);
			if (shipmentNode.getNodeType() == Node.ELEMENT_NODE) {
				Element shipmentElement = (Element) shipmentNode;
				String id = shipmentElement.getAttribute("id");
				if (id.equals(shipmentId)) {
					destination = shipmentElement.getAttribute("to");
					break;
				}
			}
		}
		return destination;
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
