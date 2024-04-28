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

public class RunFreightDuc {

	private static final Logger log = LogManager.getLogger(RunFreightDuc.class);

	private static int nuOfJspritIteration;

	public static void main(String[] args) throws ExecutionException, InterruptedException, IOException {

		for (String arg : args) {
			log.info( arg );
		}

		if ( args.length==0 ) {
			String inputPath = "./input/";
			args = new String[] {
					inputPath+ "RandomCarrier_5_NoSeed.xml",
					inputPath + "VehicleType_26t_Size24.xml",
					"1",
					"./output/Test_Freight",
			};
		}

		// extending xml name with the iteration count
		xmlNameChangeID(args);

		// changing number of runs
		int nuOfRuns = 1;
		for(int i = 0; i < nuOfRuns; i++) {
			String runId = String.valueOf(i+1);


      // ### config stuff: ###
      Config config = prepareConfig(args, runId);

      // load scenario (this is not loading the freight material):
      org.matsim.api.core.v01.Scenario scenario = ScenarioUtils.loadScenario(config);

      // load carriers according to freight config
      FreightUtils.loadCarriersAccordingToFreightConfig(scenario);

      // set # of jsprit iterations
      for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
        log.warn(
            "Overwriting the number of jsprit iterations for carrier: "
                + carrier.getId()
                + ". Value was before "
                + CarrierUtils.getJspritIterations(carrier)
                + " and is now "
                + nuOfJspritIteration);
        CarrierUtils.setJspritIterations(carrier, nuOfJspritIteration);
      }

      // Hier geschieht der Hauptteil der Arbeit: Das Zerlegen der Shipments
	  Divide sizeSelection = Divide.SizeOne;	// Auswahl der Zerlegung
      changeShipmentSize(scenario, sizeSelection);

      // count the runtime of Jsprit
      long start = System.nanoTime();

      // Solving the VRP (generate carrier's tour plans)
      FreightUtils.runJsprit(scenario);

      long end = System.nanoTime();
      double durationSec = (end - start) / 1e9;
      double durationMin = (end - start) / (1e9 * 60);


      // ## MATSim configuration:  ##
      final Controler controler = new Controler(scenario);
      controler.addOverridingModule(new CarrierModule());
      controler.addOverridingModule(
          new AbstractModule() {
            @Override
            public void install() {
              final MyEventBasedCarrierScorer carrierScorer = new MyEventBasedCarrierScorer();

              bind(CarrierScoringFunctionFactory.class).toInstance(carrierScorer);
            }
          });

      // ## Start of the MATSim-Run: ##
      controler.run();

	  // hier geschieht die Analyse der Arbeit
      // Creating the Analysis files
      RunFreightAnalysisEventbased FreightAnalysis =
          new RunFreightAnalysisEventbased(
              controler.getControlerIO().getOutputPath(),
              controler.getControlerIO().getOutputPath() + "/analyze");
      try {
        FreightAnalysis.runAnalysis();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      // Output after jsprit run (not necessary)
      new CarrierPlanWriter(FreightUtils.getCarriers(scenario))
          .write(
              controler.getControlerIO().getOutputPath() + "/analyze/jsprit_plannedCarriers.xml");

      // Output the number of destination approaches of the tour
      tourDestinationWriter(controler.getControlerIO().getOutputPath());

      runTimeOutput(durationSec, durationMin, controler);
		}
	}

	/**
	 * configuration setups
	 *
	 * @param args
	 * @param runId
	 */
	private static Config prepareConfig(String[] args, String runId) {
		Config config = ConfigUtils.createConfig();

		config.network().setInputFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/berlin/berlin-v5.5-10pct/input/berlin-v5.5-network.xml.gz");
		// more general settings
		config.controler().setOutputDirectory(args[3] + "/Run" +runId);
		config.controler().setLastIteration(0 );		// yyyyyy iterations currently do not work; needs to be fixed.  (Internal discussion at end of file.)
		config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		// freight settings
		FreightConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule( config, FreightConfigGroup.class ) ;
		freightConfigGroup.setCarriersFile(args[0]);
		freightConfigGroup.setCarriersVehicleTypesFile(args[1]);

		nuOfJspritIteration = Integer.parseInt(args[2]);
		return config;
	}

	/**
	 * method for breaking up shipments into smaller ones
	 *
	 * @param scenario
	 * @param sizeSelection  the size to break down the shipments
	 */
	private static void changeShipmentSize(org.matsim.api.core.v01.Scenario scenario, Divide sizeSelection) {

		double Boundary_value = Double.MAX_VALUE;
		for(VehicleType vehicleType : FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().values()){
			if(vehicleType.getCapacity().getOther() < Boundary_value)
			{
				Boundary_value = vehicleType.getCapacity().getOther();
			}
		}

		switch (sizeSelection) {
			case StandardSize -> {
				// wenn die Sendungsgröße nicht verändert werden soll
			}
			case SizeEight -> {
				Boundary_value = 8;
			}
			case SizeFour -> {
				Boundary_value = 4;
			}
			case SizeTwo -> {
				Boundary_value = 2;
			}
			case SizeOne -> {
				Boundary_value = 1;
			}
			default -> throw new IllegalStateException("Unexpected value: " + sizeSelection);
		}

		// method to create new shipments (hier werden die Sendungen zerlegt und neue 'kleinere' Sendungen erstellt )
		createShipment(scenario, (int) Boundary_value);

	}




	/**
	 * Building the new shipments
	 *
	 * @param scenario
	 * @param Boundary_value value of the new shipment size
	 */
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
			demandBefore = numOfShipments(carrier,demandBefore);
			deliveryServiceTimeBefore = sumServiceTime(carrier,deliveryServiceTimeBefore,1);
			pickupServiceTimeBefore= sumServiceTime(carrier,pickupServiceTimeBefore,2);

			// new shipments are created
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
			demandAfter = numOfShipments(carrier,demandAfter);
			deliveryServiceTimeAfter = sumServiceTime(carrier,deliveryServiceTimeAfter,1);
			pickupServiceTimeAfter = sumServiceTime(carrier,pickupServiceTimeAfter,2);

			// checking right amount of total of Shipment sizes, time windows
			Gbl.assertIf(demandBefore == demandAfter);
			Gbl.assertIf(deliveryServiceTimeBefore == deliveryServiceTimeAfter);
			Gbl.assertIf(pickupServiceTimeBefore == pickupServiceTimeAfter);
		}
	}

	/**
	 *  creates new smaller shipments from the old ones
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
				CarrierShipment newShipment = shipmentBuilder( carrierShipment,1, rest);
				newShipments.add(newShipment); // add the new shipment
			}

			// the new shipments are created
			if(rest != 0) {
				for (int i = 1; i <= numShipments; i++) {
					CarrierShipment newShipment = shipmentBuilder(carrierShipment,(i + 1), size);
					newShipments.add(newShipment); // add the new shipment in the temporary LinkedList
				}
			}
			else {
				for (int i = 1; i <= numShipments; i++) {
					CarrierShipment newShipment = shipmentBuilder(carrierShipment, i, size);
					newShipments.add(newShipment); // add the new shipment in the temporary LinkedList
				}
			}

			if(numShipments > 0){
				oldShipments.add(carrierShipment);
			}
		}
	}

	/**
	 * method to build new shipments
	 *
	 * @param carrierShipment the shipment
	 * @param id id of the shipment
	 * @param shipmentSize new size for the shipment
	 * @return  the new/modified CarrierShipment
	 */
	private static CarrierShipment shipmentBuilder(CarrierShipment carrierShipment, int id, int shipmentSize){
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


	// Methoden numOfShipments und sumServiceTime werden für die Überprüfung genutzt. Das die Anzahl vor und nach dem zerlegen der Sendungen gleich sind.
	/**
	 * method for counting sizes/demand of all Shipments
	 *
	 * @param carrier the already existing carrier
	 * @param demand counter to sum up all shipments
	 * @return the sum of the whole shipments
	 */
	static int numOfShipments(Carrier carrier, int demand){

		for (CarrierShipment carrierShipment : carrier.getShipments().values()){
			demand = demand + carrierShipment.getSize();
		}
		return demand;
	}

	/**
	 * method for sum up Delivery or Service Time
	 *
	 * @param carrier the already existing carrier
	 * @param serviceTime variable to sum up service time
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

	enum Divide {
		StandardSize,
		SizeEight,
		SizeFour,
		SizeTwo,
		SizeOne,


	}
	/**
	 * writes in files like the number of shipments/destinations of the tour
	 *
	 * @param outpath path to Test_freight folder
	 */
	private static void tourDestinationWriter(String outpath) {
		try {
			File inputFile = new File(outpath+"/analyze/jsprit_plannedCarriers.xml");
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document doc = dBuilder.parse(inputFile);
			doc.getDocumentElement().normalize();

			NodeList tourList = doc.getElementsByTagName("tour");
			int tourCount = tourList.getLength();

			// HashMap to store destination and its count
			HashMap<String,Integer> countShipmentToDestination = new HashMap<>();

			// new file objects
			File fileShipmentsPerDestination = new File(outpath+"/analyze/ShipmentsPerDestination.txt");
			File fileDestinationPerTour = new File(outpath+"/analyze/DestinationPerTour.txt");

			for (int i = 0; i < tourCount; i++) {
				Node tourNode = tourList.item(i);

				if (tourNode.getNodeType() == Node.ELEMENT_NODE) {
					HashMap<String, Integer>  counter = new HashMap<>(); // so that he won't count the same destination more than once for a tour
					nullHashMapBuilder(doc,counter);
					HashMap<String, Integer> tourDestinationCounter = new HashMap<>(); // counting right amount of shipments destinations for the every tour

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


								if ( tourDestinationCounter.containsKey(destination) ) {

									int countShip1 = tourDestinationCounter.get(destination);
									tourDestinationCounter.put(destination, countShip1 + 1);

								} else{
									tourDestinationCounter.put(destination, 1);
								}

								if ( countShipmentToDestination.containsKey(destination)) {

									int countShip2 = countShipmentToDestination.get(destination);
									countShipmentToDestination.put(destination, countShip2+1);

								}else{
									countShipmentToDestination.put(destination,1);
								}


							}
						}
					}
					// writes in a textfile the delivered shipments for every tour in the scenario
					outputTourDestination(tourDestinationCounter,fileDestinationPerTour,1);
				}
			}

			// writes in a textfile how many shipments need to be delivered to the respective location
			outputTourDestination(countShipmentToDestination,fileShipmentsPerDestination,2);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * writes the number of shipments for the destination and
	 * the deliveries for each tour in a text file
	 *
	 * @param map HashMap with the information
	 */
	private static void outputTourDestination(HashMap<String,Integer> map, File file, int num ){

		BufferedWriter bf = null;

		// "108280", "104051", "15863", "143950", "143810"
		HashMap<String,String> destinationNames = new HashMap<>();
		destinationNames.put("108280","Hellersdorf");
		destinationNames.put("104051","Westend");
		destinationNames.put("15863","Blankenburg");
		destinationNames.put("143950","Wannsee");
		destinationNames.put("143810","Reinickendorf");

		switch(num) {

			case 1: {
				try {

					// create new BufferedWriter for the output file
					bf = new BufferedWriter(new FileWriter(file, true));


					bf.write("Zielorte der Tour  : \t  "+map);


					bf.newLine();

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
			case 2: {
				try {

					// create new BufferedWriter for the output file
					bf = new BufferedWriter(new FileWriter(file));

					// iterate map entries
					for (Map.Entry<String, Integer> entry :
							map.entrySet()) {

						// Ausgabe: wieviele Sendungen müssen zum jeweiligen Ort transportiert werden (und zur Kontrolle, ob die Anzahl (bei zufällig verteilter Sendungen) zum jeweiligen Ort korrekt ist)
						bf.write("Zielort: " + entry.getKey() + "  \t  Anzahl Sendungen für "+destinationNames.get(entry.getKey())+": " + entry.getValue());


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

	/**
	 * method for that returns the destination of a shipment
	 *
	 * @param doc document file of a xml file
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


	/**
	 * writing the runtime in a text file
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
	 * renames the ID name of a xml file
	 *
	 * @param args String array with arguments
	 */
	private static void xmlNameChangeID(String[] args) {
		try {
			// loading XML-document
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
}
