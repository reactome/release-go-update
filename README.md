# GO Update

This tool will update the GO terms in the database.

It will query read the entire `gene_ontology_ext.obo` file and the entire`ec2go` file and then use the contents of these files to create/update/delete GO terms in the database.

## Logging

Reports will be logged in a few different files, in the `logs` directory:

### GO\_Update\_newGoTerms-${datetime}.log 
 This file will have a list of all new GO terms that were created during the execution of this program. The file is a TSV, with three fields: DB_ID, GO Accession, and the details of the object. Example:

```
9607472	0106099	{def=Catalysis of the reaction 2-dehydro-3-deoxy-L-rhamnonate = pyruvate + (S)-lactaldehyde., name=2-keto-3-deoxy-L-rhamnonate aldolase activity, namespace=molecular_function, is_a=[0016832]}
9607473	0106126	{def=The lipid bilayer surrounding a reservosome., name=reservosome membrane, namespace=cellular_component, is_a=[0044437], part_of=[0106123]}
```

### GO\_newMolecularFunctions-${datetime}.log 
 This file is similar to the newGoTerms file, but instead of ALL types of GO terms, it is only the Molecular Functions that are listed. This is similar to functionality that was being introduced into the old Perl version of this program. The file will look like this:
 
```
9607472	0106099	2-keto-3-deoxy-L-rhamnonate aldolase activity
9607483	0106105	Ala-tRNA(Thr) hydrolase activity
```

### GO\_Update\_obosolete-${datetime}.log 
 This file will have information about obsolete GO terms. Usually, these terms get deleted. However, if they have referrers that are not GO terms themselves, then they cannot be deleted. Previously, in the Perl version of the "GO Update" *all* obsolete GO terms were deleted, and referrers were modified to point to GO terms whose accessions were referenced in the "replaced_by", "consider", or "alternate" fields in the GO file. Recently (July 2018), it was requested to modify the algorithm in such a way so that GO terms with referrers that are not GO terms would be logged and a curator would manually resolve the issue.
The file may look like this:

```
11:05:33.355 [main] WARN  org.reactome.release.goupdate.GoTermsUpdater - GO:0035105 ([[GO_BiologicalProcess:161489] sterol regulatory element binding protein import into nucleus]) marked as OBSOLETE!
11:05:33.742 [main] WARN  org.reactome.release.goupdate.GoTermsUpdater - GO:0035048 ([[GO_BiologicalProcess:160518] splicing factor protein import into nucleus]) marked as OBSOLETE!
11:05:33.960 [main] WARN  org.reactome.release.goupdate.GoTermsUpdater - GO:0072317 ([[GO_MolecularFunction:9037398] glucan endo-1,3-beta-D-glucosidase activity involved in ascospore release from ascus]) marked as OBSOLETE!
...
11:05:54.786 [main] INFO  org.reactome.release.goupdate.GoTermsUpdater - Instance "[GO_BiologicalProcess:82625] regulation of cAMP catabolic process" (GO:0030820) has referrers but they will not prevent deletion:
11:05:54.787 [main] INFO  org.reactome.release.goupdate.GoTermsUpdater - 	2 instanceOf referrers.

```

### GO\_Update\_reconciliation-${datetime}.log 
 This file will have reconciliation reports. The GO Update program will try to reconcile the database with what it read from the files. Any differences will be reported. Duplicate GO Accessions may also be reported here. Example:

```
11:18:38.663 [main] WARN  org.reactome.release.goupdate.GoTermsUpdater - GO Accession 0061665 appears 2 times in the database. It should probably only appear once.
11:18:39.203 [main] WARN  org.reactome.release.goupdate.GoTermsUpdater - GO Accession 0000250 appears 2 times in the database. It should probably only appear once.
11:18:40.193 [main] ERROR org.reactome.release.goupdate.GoTermsUpdater - Reconciliation error: GO:0000189; Attribute: "definition";
	Value from file: "OBSOLETE. The directed movement of a MAP kinase to the nucleus upon activation.";
	Value from database: "The directed movement of a MAP kinase to the nucleus upon activation."
```

Note: If a GO accession cannot be deleted, it will probably trigger a reconciliation error since the name/definition in file may contain "OBSOLETE" but the data in the database does not get updated with this information, since the GO term really should be deleted.

### GO\_Update\_updatedGOTerms-${datetime}.log
 This file will have details about GO terms that were updated. Example:
 
```
GO:0072184 ([GO_BiologicalProcess:938351] renal vesicle progenitor cell differentiation) now has relationship "instanceOf" referring to , GO:0061005 ([GO_BiologicalProcess:528323] cell differentiation involved in kidney development)
GO:0072184 ([GO_BiologicalProcess:938351] renal vesicle progenitor cell differentiation) now has relationship "componentOf" referring to , GO:0072087 ([GO_BiologicalProcess:528957] renal vesicle development)
GO:0106041 ([GO_BiologicalProcess:9016333] positive regulation of GABA-A receptor activity) now has relationship "instanceOf" referring to , GO:0106040 ([GO_BiologicalProcess:9016332] regulation of GABA-A receptor activity)
GO:0106041 ([GO_BiologicalProcess:9016333] positive regulation of GABA-A receptor activity) now has relationship "instanceOf" referring to , GO:2000273 ([GO_BiologicalProcess:1221437] positive regulation of signaling receptor activity)
```

### GO\_Update-${datetime}.log
 This is the main GO Update log file. It will contain any other messages that are emitted during the execution of this program, as well as the pre- and post-update duplicate GO accessions reports.
 
## Configuration

This application requires a properties file at `./go-update.properties` that contains the necessary configuration information. It should look something like this:

```
db.host=${hostname}
db.user=${username}
db.password=${password}
db.name=gk_central
db.port=3306
testMode=true
person.id=123456789
pathToGOFile=src/main/resources/go.obo
pathToEC2GOFile=src/main/resources/ec2go
```

- The db.* values are required to make a database connection.
- `testMode` can be set to `true` if you want to see what the results might look like, but not commit them to the database. Set this to false when you _do_ want the rsults to get committed.
- person.id - This will be used as the Person ID for the InstanceEdits that this program creates.
- pathToGOFile - the path to `gene_ontology_ext.obo`. This file contains the GO terms. You can download this file from [http://geneontology.org/ontology/obo_format_1_2/gene_ontology_ext.obo](http://geneontology.org/ontology/obo_format_1_2/gene_ontology_ext.obo).
- pathToEC2GOFile - the path to `ec2go`. This file contains a mapping of EC numbers mapped to GO accessions. You can download this file from [http://geneontology.org/external2go/ec2go](http://geneontology.org/external2go/ec2go).

Pass this path to this properties file to the program as the first argument.

## Compiling & Running

This is a Java application which requries a Java 8+ environment. You will need maven and a full JDK to compile.

To compile the application, run this command:

```
$ mvn clean compile package
```

If this is successful, you should see a JAR file in the `target` directory, with a name like `chebi-update-VERSION_NUMBER-jar-with-dependencies.jar`. This is the file you will run.

To run the program, execute this command:
```
$ java -jar target/go-update-0.0.1-SNAPSHOT-jar-with-dependencies.jar ./go-update.properties
```

Note: You will need to release-common-lib to build this project. The best way to get this is to compile it from the source [here](../release-common-lib).
