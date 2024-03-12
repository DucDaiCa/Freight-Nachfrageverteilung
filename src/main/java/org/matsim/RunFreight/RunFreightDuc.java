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

	//TODO: Ein paar generelle Anmerkungen von mir (KMT) - von schneller Durchsicht. Kein anspruch auf Vollständigkeit-
	// 1.) Bitte mal aufräumen und alles nicht notwendige raus werfen. -> erhöht massiv die Übersichtlichkeit
	// 2.) Interne Methoden/Funktionen "private" machen.
	// 3.) Auch Kommentare von mir weiter unten ansehen
	// 4.) Empfehlung: Mache die Bezichnugn des Outputs von deinen Settings abhängig.
	// Dann kommt man später nicht so leicht durcheinander was eingestellt wurde.
	// 5.) Alle Settings möglichst weit nach oben!
	// 6.) Schreibe dir doch entsprechend von dir hinzugefügte Infois/Analysen, die du bracuhst nicht nur
	// in die Konsole (System.out.println(..) sondern in eine entsprechende Datei, sodass du es dann gut übernehmen kannst
	// 7.) Eventuell hilft es auch, wenn einige Hilfs/Analyse-Methoden in eine andere Klasse verschoben werden
	// 8.) Gerne in Methoden im Javadoc Kommentar auch angeben, warum etwas gemacht wird.
	// 9.) Mal bitte schauen, ob Bennungen sinnvoll sind. z.B: ist Plot "zeichnen" aber bei dir das Setting der Aufteilung.
	// ....


	public static void main(String[] args) throws ExecutionException, InterruptedException, IOException {

		for (String arg : args) {
			log.info( arg );
		}

		if ( args.length==0 ) {
			String inputPath = "./input/";
			args = new String[] {
					inputPath+ "RandomCarriers_4_RS3711.xml",
					inputPath + "VehicleTypes_26t_Size24.xml",
					"10",
					"./output/Test_Freight",
			};
		}

		// extending xml name with the iteration count (2)
		xmlNameChangeID(args);

		int nuOfRuns = 10;
		for(int i = 0; i < nuOfRuns; i++) {
			String runId = String.valueOf(i+1);


      // ### config stuff: ###
      Config config = prepareConfig(args, runId);

      // load scenario (this is not loading the freight material):
      org.matsim.api.core.v01.Scenario scenario = ScenarioUtils.loadScenario(config);

      // load carriers according to freight config
      FreightUtils.loadCarriersAccordingToFreightConfig(scenario);

      // Die zufallsverteilung sollte mMn aus unabhängiger Schritt vorab erfolgen. Das macht es für
      // dich einfacher die Übersicht zu behalten. Mit den daraus kommenden CarrierFiles kannst du
      // dann
      // in die Simulation gehen.
      // changes Shipment sizes randomly (1) then comment code and uncomment (2) above
      // CreateCarriersWithRandomDistribution.randomShipmentDistribution_fromDuc(scenario,args);

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

      // Write out the original carriers file - before any modification is done
      new CarrierPlanWriter(FreightUtils.getCarriers(scenario))
          .write("output/originalCarriers_Run"+runId+".xml");

      // Hier geschieht der Hauptteil der Arbeit: Das Aufteilen der Shipments :)
		Divide sizeSelection = Divide.SizeOne;
      changeShipmentSize(scenario, sizeSelection);

      // output before jsprit run (not necessary)
      new CarrierPlanWriter(FreightUtils.getCarriers(scenario))
          .write("output/jsprit_unplannedCarriers_Run"+runId+".xml");
      // (this will go into the standard "output" directory.  note that this may be removed if this
      // is also used as the configured output dir.)


      // count the runtime of Jsprit and MATSim
      long start = System.nanoTime();

      // Solving the VRP (generate carrier's tour plans)
      FreightUtils.runJsprit(scenario);

      long end = System.nanoTime();
      double durationSec = (end - start) / 1e9;
      double durationMin = (end - start) / (1e9 * 60);
      // System.out.println("Zeit: "+durationMS+" s");

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
			//TODO Warum schreibst du den nochmal raus. der müsste doch schon in den normalen output_* Dateien drinnen sein? Kai' feb 24
      new CarrierPlanWriter(FreightUtils.getCarriers(scenario))
          .write(
              controler.getControlerIO().getOutputPath() + "/analyze/jsprit_plannedCarriers.xml");

      // Output the number of destination approaches of the tour
      tourDestinationCounter(controler.getControlerIO().getOutputPath());

      runTimeOutput(durationSec, durationMin, controler);
		}
	}

	/**
	 * method for breaking up Shipments into smaller ones
	 */
	private static void changeShipmentSize(org.matsim.api.core.v01.Scenario scenario, Divide sizeSelection) {

		//Todo: So eine generelle Einstellung sollte mMn sehr weit nach oben gehen. Aus meiner Sicht kann
		//dann das Setting auch der Methode übergeben werden (muss aber nicht).
		//Divide mySelection =  Divide.SizeOne;
		double Boundary_value = Double.MAX_VALUE;
		for(VehicleType vehicleType : FreightUtils.getCarrierVehicleTypes(scenario).getVehicleTypes().values()){
			if(vehicleType.getCapacity().getOther() < Boundary_value)
			{
				Boundary_value = vehicleType.getCapacity().getOther();
			}
		}

		switch (sizeSelection) {
			case StandardSize -> {
				// Sendungsgröße wird nicht verändert
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

		// method to create new shipments ( hier werden die Shipments aufgeteilt und neu erstellt )
		createShipment(scenario, (int) Boundary_value);

	}

	/**
	 * renames the ID names of a xml file
	 * //TODO Warum? Was soll da aktualisiert werden? ISt derzeit eh nicht in Verwendung.
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
	 * counts how often a destination was driven to... as part of a tour
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
			HashMap<String, Integer> DrivenToDestination = new HashMap<>();

			HashMap<String,Integer> countShipmentToDestination = new HashMap<>();

			// new file objects
			File fileDestination = new File(outpath+"/analyze/Destination_Carrier.txt");
			File fileShipmentsPerDestination = new File(outpath+"/analyze/ShipmentsPerDestination.txt");
			File fileDestinationPerTour = new File(outpath+"/analyze/DestinationPerTour.txt");

			for (int i = 0; i < tourCount; i++) {
				Node tourNode = tourList.item(i);

				if (tourNode.getNodeType() == Node.ELEMENT_NODE) {
					HashMap<String, Integer>  counter = new HashMap<>(); // so that he won't count the same destination more than once for a tour
					nullHashMapBuilder(doc,counter);
					HashMap<String, Integer> tourDestinationCounter = new HashMap<>(); //counting right amount of shipments destinations for the every tour

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
								if (DrivenToDestination.containsKey(destination) ) {

									if(counter.get(destination)== 0) {
										int count = DrivenToDestination.get(destination);
										DrivenToDestination.put(destination, count + 1);
										counter.put(destination, 1);
									}
								} else {
									DrivenToDestination.put(destination, 1);
									counter.put(destination, 1);
								}

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
					// writes in a file the shipments for every tour for the scenario
					System.out.println("Zielorte der Fahrt: "+tourDestinationCounter);
					outputTourDestination(tourDestinationCounter,fileDestinationPerTour,2);
				}
			}

			// writes in a file how many shipments need to be delivered to the respective location
			outputTourDestination(countShipmentToDestination,fileShipmentsPerDestination,3);
			System.out.println("\nAnzahl Sendungen zum jeweiligem Ort: "+ countShipmentToDestination+"\n" );

			// writes in a file the destinations and how often the place was stopped
			outputTourDestination(DrivenToDestination,fileDestination,1);
			for (Map.Entry<String, Integer> entries : DrivenToDestination.entrySet()) {
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
					bf = new BufferedWriter(new FileWriter(file));

					// iterate map entries
					for (Map.Entry<String, Integer> entry :
							map.entrySet()) {

						// put key and value separated by a colon
						//bf.write("Zielort: " + entry.getKey() + "  \t   Wie oft hingefahren: " + entry.getValue());
						bf.write("Zielort: " + entry.getKey() + "  \t   Wie oft zu "+destinationNames.get(entry.getKey())+" hingefahren: " + entry.getValue());
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
			case 2: {
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
			case 3: {
				try {

					// create new BufferedWriter for the output file
					bf = new BufferedWriter(new FileWriter(file));

					// iterate map entries
					for (Map.Entry<String, Integer> entry :
							map.entrySet()) {

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
	 * Building the new shipments
	 *
	 * @param Boundary_value value of the new shiopment size
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

			// here checking if the numbers stayed the same
			Gbl.assertIf(demandBefore == demandAfter);
			Gbl.assertIf(deliveryServiceTimeBefore == deliveryServiceTimeAfter);
			Gbl.assertIf(pickupServiceTimeBefore == pickupServiceTimeAfter);
		}
	}

	/**
	 * the shipment builder
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
	 * method for creating the new Shipments
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
	static int numOfShipments(Carrier carrier, int demand){

		for (CarrierShipment carrierShipment : carrier.getShipments().values()){
			demand = demand + carrierShipment.getSize();
		}
		return demand;
	}

	/**
	 * method for counting sizes/demand of all Shipments
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

	//TODO: 1.) Plot heißt übersetzt "zeichnen". Das passt hier leider gar nicht.
	//Todo: 2.) Aus meiner Sich müsste noch eine Option "lasse es wie es ist" mit rein und entsprechend implentiert werden.
	enum Divide {
		StandardSize,
		SizeEight,
		SizeFour,
		SizeTwo,
		SizeOne,


	}

	/**
	 * method for counting sizes/demand of all Shipments
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
