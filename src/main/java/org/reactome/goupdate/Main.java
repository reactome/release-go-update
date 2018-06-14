package org.reactome.goupdate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.persistence.TransactionsNotSupportedException;

/**
 * @author sshorser
 *
 */
public class Main
{

	/**
	 * @param args
	 * @throws SQLException 
	 */
	public static void main(String[] args) throws SQLException
	{
		// First part:
		// 1) Get the GO files:
		// - http://geneontology.org/ontology/obo_format_1_2/gene_ontology_ext.obo
		// - http://geneontology.org/external2go/ec2go
		// 2) from database, get list of all things where:
		//    biological_process=GO_BiologicalProcess, molecular_function=GO_MolecularFunction, cellular_component=GO_CellularComponent 
		// 3) Read gene_ontology_ext.obo
		// 4) Update objects from Database based on GO file.
		// 5) print Wiki output.
		//
		// Second part:
		// 1) Read ec2go file
		// 2) extact EC to GO mapping.
		// 3) Update GO objects in Database.
		//
		// ...Of course, we could just do these together in one program: Read both files and populate one data structure containing everything.
		Long startTime = System.currentTimeMillis();
		String pathToGOFile = "src/main/resources/gene_ontology_ext.obo";
		String pathToEC2GOFile = "src/main/resources/ec2go";
		try
		{
			// TODO: Read these values from a properties file.
			MySQLAdaptor adaptor = new MySQLAdaptor("localhost","gk_central","root","root",3306);
			GKInstance goRefDB = null;
			try
			{
				goRefDB = ((Set<GKInstance>) adaptor.fetchInstanceByAttribute(ReactomeJavaConstants.ReferenceDatabase, ReactomeJavaConstants.name, "=","GO")).stream().findFirst().get();
				System.out.println("RefDB for GO: "+goRefDB.toString());
			}
			catch (Exception e1)
			{
				System.out.println("Couldn't even get a reference to the GO ReferenceDatabase object. There's no point in continuing, so this progam will exit. :(");
				e1.printStackTrace();
				System.exit(1);
			}
			try
			{
				adaptor.startTransaction();
			}
			catch (TransactionsNotSupportedException e1)
			{
				e1.printStackTrace();
				System.out.println("This program should run within a transaction. Aborting.");
				System.exit(1);
			}

			// Load the file.
			List<String> goLines = Files.readAllLines(Paths.get(pathToGOFile));
			List<String> ec2GoLines = Files.readAllLines(Paths.get(pathToEC2GOFile));

			GoTermsUpdater goTermsUpdator = new GoTermsUpdater(adaptor, goLines, ec2GoLines);
			
			StringBuilder report = goTermsUpdator.updateGoTerms();
			System.out.println(report);

			adaptor.rollback();	
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		Long endTime = System.currentTimeMillis();
		System.out.println("Elapsed time (minutes): " + TimeUnit.MILLISECONDS.toMinutes(endTime-startTime));
	}
}
