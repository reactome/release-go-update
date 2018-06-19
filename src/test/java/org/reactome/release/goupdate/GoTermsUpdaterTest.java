package org.reactome.release.goupdate;

import static org.mockito.Mockito.mock;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaClass;
import org.gk.schema.InvalidAttributeException;
import org.gk.schema.Schema;
import org.gk.schema.SchemaClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.reactome.release.common.database.InstanceEditUtils;
import org.reactome.release.goupdate.GoTermsUpdater;

@RunWith(PowerMockRunner.class)
@MockitoSettings(strictness = Strictness.WARN)
@PowerMockIgnore({"javax.management.*","javax.script.*"})
@PrepareForTest({InstanceEditUtils.class, GoTermsUpdater.class})
public class GoTermsUpdaterTest
{

	private static final String sampleGoText = "\n[Term]\n" + 
			"id: GO:0000001\n" + 
			"name: mitochondrion inheritance\n" + 
			"namespace: biological_process\n" + 
			"def: \"The distribution of mitochondria, including the mitochondrial genome, into daughter cells after mitosis or meiosis, mediated by interactions between mitochondria and the cytoskeleton.\" [GOC:mcc, PMID:10873824, PMID:11389764]\n" + 
			"synonym: \"mitochondrial inheritance\" EXACT []\n" + 
			"is_a: GO:0048308 ! organelle inheritance\n" + 
			"is_a: GO:0048311 ! mitochondrion distribution\n" + 
			"\n" + 
			"[Term]\n" + 
			"id: GO:0000002\n" + 
			"name: mitochondrial genome maintenance\n" + 
			"namespace: biological_process\n" + 
			"def: \"The maintenance of the structure and integrity of the mitochondrial genome; includes replication and segregation of the mitochondrial chromosome.\" [GOC:ai, GOC:vw]\n" + 
			"is_a: GO:0007005 ! mitochondrion organization\n" + 
			"\n" + 
			"[Term]\n" + 
			"id: GO:0000003\n" + 
			"name: reproduction\n" + 
			"namespace: biological_process\n" + 
			"alt_id: GO:0019952\n" + 
			"alt_id: GO:0050876\n" + 
			"def: \"The production of new individuals that contain some portion of genetic material inherited from one or more parent organisms.\" [GOC:go_curators, GOC:isa_complete, GOC:jl, ISBN:0198506732]\n" + 
			"subset: goslim_agr\n" + 
			"subset: goslim_chembl\n" + 
			"subset: goslim_generic\n" + 
			"subset: goslim_pir\n" + 
			"subset: goslim_plant\n" + 
			"subset: gosubset_prok\n" + 
			"synonym: \"reproductive physiological process\" EXACT []\n" + 
			"xref: Wikipedia:Reproduction\n" + 
			"is_a: GO:0008150 ! biological_process\n" + 
			"disjoint_from: GO:0044848 ! biological phase\n" + 
			"\n" + 
			"[Term]\n" + 
			"id: GO:0000005\n" + 
			"name: obsolete ribosomal chaperone activity\n" + 
			"namespace: molecular_function\n" + 
			"def: \"OBSOLETE. Assists in the correct assembly of ribosomes or ribosomal subunits in vivo, but is not a component of the assembled ribosome when performing its normal biological function.\" [GOC:jl, PMID:12150913]\n" + 
			"comment: This term was made obsolete because it refers to a class of gene products and a biological process rather than a molecular function.\n" + 
			"synonym: \"ribosomal chaperone activity\" EXACT []\n" + 
			"is_obsolete: true\n" + 
			"consider: GO:0042254\n" + 
			"consider: GO:0044183\n" + 
			"consider: GO:0051082\n" +
			"\n"
			+"[Term]\n"+
			"id: GO:0000033\n" + 
			"name: duplicate test!\n" + 
			"namespace: biological_process\n" + 
			"def: \"testing stuff.\n" + 
			"is_a: GO:0048309\n" +
			"relationship: part_of GO:0048308\n" +
			"relationship: has_part GO:0448308\n" +
			"\n"
			+"[Term]\n"+
			"id: GO:0000033\n" + 
			"name: duplicate test!\n" + 
			"namespace: biological_process\n" + 
			"def: \"testing stuff.\n" + 
			"is_a: GO:0048309 \n" +
			"relationship: negatively_regulates GO:0048308 \n" +
			"\n"
			+"[Term]\n"+
			"id: GO:0000009\n" + 
			"name: test term\n" + 
			"relationship: part_of: GO:0048308\n" +
			"namespace: biological_process\n" + 
			"pending_obsoletion\n"+
			"def: \"testing stuff.\n" + 
			"is_a: GO:0048309\n" +
			"\n"
			+"[Term]\n"+
			"id: GO:3070009\n" + 
			"name: test term\n" + 
			"relationship: part_of: GO:0048308\n" +
			"namespace: biological_process\n" + 
			"def: \"testing stuff.\n" +
			"relationship: positively_regulates GO:0048309\n" +
			"is_obsolete: true\n" + 
			"\n"
			+"[Term]\n"+
			"id: GO:00000099\n" + 
			"name: test term\n" + 
			"namespace: biological_process\n" + 
			"def: \"testing stuff.\n" + 
			"is_a: GO:0048309 \n" +
			"replaced_by: GO:12312312\n"+
			"relationship: part_of GO:0048308\n" +
			"relationship: regulates GO:0048308\n" +
			"\n";
	
	private static final String sampleEc2GoText = "! Generated on 2018-06-04T11:27Z from the ontology 'go' with data version: 'releases/2017-03-31'\n" + 
			"!\n" + 
			"EC:1 > GO:N-ethylmaleimide reductase activity ; GO:00000099\n" + 
			"EC:1 > GO:oxidoreductase activity ; GO:0000003\n" + 
			"EC:1 > GO:reduced coenzyme F420 dehydrogenase activity ; GO:0043738\n" + 
			"EC:1 > GO:sulfur oxygenase reductase activity ; GO:00000099\n" + 
			"EC:1 > GO:malolactic enzyme activity ; GO:0043883\n" + 
			"EC:1 > GO:NADPH:sulfur oxidoreductase activity ; GO:0043914\n" + 
			"EC:1.2 > GO:epoxyqueuosine reductase activity ; GO:0000003\n" ;
	
	@Mock
	MySQLAdaptor dba;

	@Mock
	GKInstance mockRefDB;

	@Mock
	GKSchemaClass mockSchemaClass;
	
	@Mock
	Schema mockSchema;

	@Mock
	GKInstance mockGoTerm;
	
	@Before
	public void setup() throws Exception 
	{
		MockitoAnnotations.initMocks(this);
		
		PowerMockito.mockStatic(InstanceEditUtils.class);
		Mockito.when(dba.getSchema()).thenReturn(mockSchema);
		Mockito.when(mockSchema.getClassByName(anyString())).thenReturn(mockSchemaClass );
		PowerMockito.whenNew(GKInstance.class).withArguments(mockSchemaClass).thenReturn(mockGoTerm );
	}
	
	@Test
	public void testUpdateGoTerms() throws Exception
	{
		Mockito.when(dba.fetchInstanceByAttribute(ReactomeJavaConstants.ReferenceDatabase, ReactomeJavaConstants.name, "=","GO")).thenReturn(new HashSet<GKInstance>(Arrays.asList(mockRefDB)));

		Mockito.doNothing().when(mockGoTerm).setAttributeValue(anyString(), anyString());
		Mockito.doNothing().when(mockGoTerm).setAttributeValue(anyString(), any(GKInstance.class));

		GKInstance biologicalProcess = mock(GKInstance.class);
		Mockito.when(biologicalProcess.getAttributeValue(ReactomeJavaConstants.accession)).thenReturn("00000099");
		Mockito.when(mockSchemaClass.getName()).thenReturn(ReactomeJavaConstants.GO_BiologicalProcess).thenReturn(ReactomeJavaConstants.GO_MolecularFunction).thenReturn(ReactomeJavaConstants.GO_MolecularFunction);
		Mockito.when(biologicalProcess.getSchemClass()).thenReturn(mockSchemaClass);
		Mockito.when(dba.fetchInstancesByClass(ReactomeJavaConstants.GO_BiologicalProcess)).thenReturn(Arrays.asList(biologicalProcess));
		
		GKInstance biologicalProcess2 = mock(GKInstance.class);
		Mockito.when(biologicalProcess2.getAttributeValue(ReactomeJavaConstants.accession)).thenReturn("0000003");
		Mockito.when(biologicalProcess2.getSchemClass()).thenReturn(mockSchemaClass);
		Mockito.when(dba.fetchInstancesByClass(ReactomeJavaConstants.GO_MolecularFunction)).thenReturn(Arrays.asList(biologicalProcess2));
		
		GKInstance molecularFunction = mock(GKInstance.class);
		Mockito.when(molecularFunction.getAttributeValue(ReactomeJavaConstants.accession)).thenReturn("3070009");
		Mockito.when(molecularFunction.getSchemClass()).thenReturn(mockSchemaClass);
		Mockito.when(dba.fetchInstancesByClass(ReactomeJavaConstants.GO_MolecularFunction)).thenReturn(Arrays.asList(molecularFunction));
		
		
		Mockito.when(dba.storeInstance(any(GKInstance.class))).thenReturn(123456L);
		
		GKInstance mockInstanceEdit = mock(GKInstance.class);
		Mockito.when(InstanceEditUtils.createInstanceEdit(any(MySQLAdaptor.class), any(Long.class), anyString())).thenReturn(mockInstanceEdit);
		
		List<String> goLines = Arrays.asList(sampleGoText.split("\n"));
		List<String> ec2GoLines = Arrays.asList(sampleEc2GoText.split("\n"));
		long personID = 12345L;
		
		GoTermsUpdater updater = new GoTermsUpdater(dba, goLines, ec2GoLines, personID);
		
		GoTermInstanceModifier modifier = mock(GoTermInstanceModifier.class);
		
		Mockito.doNothing().when(modifier).createNewGOTerm(any(Map.class), any(Map.class), anyString(), anyString(), any(GKInstance.class));
		Mockito.doNothing().when(modifier).updateGOInstance(any(Map.class), any(Map.class), anyString(), any(StringBuffer.class));
		Mockito.doNothing().when(modifier).updateRelationship(any(Map.class), any(Map.class), anyString(), anyString(), any(StringBuffer.class));
		Mockito.doNothing().when(modifier).deleteGoInstance(any(Map.class), any(Map.class), any(StringBuffer.class));
		
		PowerMockito.whenNew(GoTermInstanceModifier.class).withAnyArguments().thenReturn(modifier);
		
		try
		{
			updater.updateGoTerms();
		}
		catch(Exception e)
		{
			e.printStackTrace();
			fail();
		}
	}
	
}
