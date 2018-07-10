package org.reactome.release.goupdate;


import static org.mockito.Mockito.mock;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;
import org.gk.schema.GKSchemaClass;
import org.gk.schema.InvalidAttributeException;
import org.gk.schema.InvalidAttributeValueException;
import org.gk.schema.Schema;
import org.gk.schema.SchemaAttribute;
import org.gk.schema.SchemaClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.api.mockito.expectation.PowerMockitoStubber;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.hamcrest.CoreMatchers.anything;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.*;
import org.reactome.release.goupdate.GoUpdateConstants;

@RunWith(PowerMockRunner.class)
@PrepareForTest({GoTermInstanceModifier.class, InstanceDisplayNameGenerator.class})
@MockitoSettings(strictness = Strictness.WARN)
@PowerMockIgnore({"javax.management.*","javax.script.*"})
public class GoTermInstanceModifierTest 
{
	private static final String TEST_GO_ID = "00001";

	@Mock(name = "adaptor")
	private MySQLAdaptor adaptor;
	


	@Mock
	private Schema mockSchema;
	
	@Mock
	private GKSchemaClass biologicalProcessGKSchemaClass;
	
//	@Mock
//	private SchemaClass biologicalProcessSchemaClass;
	
	@Mock
	private GKSchemaClass molecularFunctionGKSchemaClass;
	
//	@Mock
//	private SchemaClass molecularFunctionSchemaClass;
	
	@Mock
	private GKInstance mockInstanceEdit;
	
	@Mock
	private GKInstance mockRefDB;
	
	@Mock
	private GKInstance newGoTerm;
	
	@Mock
	private SchemaAttribute mockAttribute;
	
//	@InjectMocks
//	GoTermInstanceModifier modifier = new GoTermInstanceModifier(adaptor, mockInstanceEdit);
	
	@Before
	public void setup() throws InvalidAttributeException, Exception
	{
		MockitoAnnotations.initMocks(this);
		
		Mockito.when(molecularFunctionGKSchemaClass.getName()).thenReturn(ReactomeJavaConstants.GO_MolecularFunction);
		Mockito.when(biologicalProcessGKSchemaClass.getName()).thenReturn(ReactomeJavaConstants.GO_BiologicalProcess);

		// Mock getting a SchemaClass
		Mockito.when(biologicalProcessGKSchemaClass.getName()).thenReturn(ReactomeJavaConstants.GO_BiologicalProcess);
		Mockito.when(molecularFunctionGKSchemaClass.getName()).thenReturn(ReactomeJavaConstants.GO_MolecularFunction);
		Mockito.when(mockAttribute.isMultiple()).thenReturn(false);
		Mockito.when(biologicalProcessGKSchemaClass.getAttribute(anyString())).thenReturn(mockAttribute);

		// Mock GO Instance
		Mockito.doNothing().when(newGoTerm).setAttributeValue(ReactomeJavaConstants.accession, TEST_GO_ID);
		Mockito.doNothing().when(newGoTerm).setAttributeValue(anyString(), any());
		Mockito.doNothing().when(newGoTerm).setAttributeValue(any(GKSchemaAttribute.class), any(Object.class));
		Mockito.when(newGoTerm.toString()).thenReturn("[Test New GO Term Instance]");
		Mockito.when(newGoTerm.getDBID()).thenReturn(1122334455L);
		PowerMockito.whenNew(GKInstance.class).withArguments(biologicalProcessGKSchemaClass).thenReturn(newGoTerm);
		PowerMockito.whenNew(GKInstance.class).withArguments(molecularFunctionGKSchemaClass).thenReturn(newGoTerm);
	}
	
	@Test
	public void updateRelationshipsTest() throws InvalidAttributeException, Exception
	{
		Map<String, List<GKInstance>> allGoInstances = new HashMap<String,List<GKInstance>>();
		GKInstance otherGoTerm = mock(GKInstance.class);
		Mockito.when(otherGoTerm.getDBID()).thenReturn(12121212L);
		Mockito.when(otherGoTerm.getAttributeValue(ReactomeJavaConstants.accession)).thenReturn("54321");
		Mockito.doNothing().when(newGoTerm).addAttributeValue(anyString(), any(GKInstance.class));
		Mockito.doNothing().when(adaptor).updateInstanceAttribute(any(GKInstance.class), anyString());
		allGoInstances.put("54321", Arrays.asList(otherGoTerm));
		
		GoTermInstanceModifier modifier = new GoTermInstanceModifier(adaptor, newGoTerm, mockInstanceEdit);
		Map<String, Object> goProps = new HashMap<String, Object>();
		goProps.put(GoUpdateConstants.IS_A, Arrays.asList("54321"));
		modifier.updateRelationship(allGoInstances, goProps , GoUpdateConstants.IS_A, "isA");
		
		// now, do it again, but with the other object missing.

		allGoInstances.remove("54321");
		modifier.updateRelationship(allGoInstances, goProps , GoUpdateConstants.IS_A, "isA");
	}
	
	@Test
	public void deleteGoTermTest() throws InvalidAttributeException, Exception
	{
		Mockito.when(newGoTerm.getAttributeValue(ReactomeJavaConstants.accession)).thenReturn(TEST_GO_ID);
		
		Map<String, Map<String, Object>> goTerms = new HashMap<String, Map<String,Object>>();
		Map<String, Object> goTermDetail = new HashMap<String, Object>();
		goTermDetail.put(GoUpdateConstants.NAME, "Test");
		goTermDetail.put(GoUpdateConstants.DEF, "This is a test go term");
		
		goTerms.put(TEST_GO_ID, goTermDetail);
		GoTermInstanceModifier modifier = new GoTermInstanceModifier(adaptor, newGoTerm, mockInstanceEdit);
		Map<String, List<GKInstance>> allGoInstances = new HashMap<String,List<GKInstance>>();
		
		// now, execute the DELETE
		StringBuffer sb = new StringBuffer();
		modifier.deleteGoInstance(goTerms, allGoInstances , sb);
		assert(sb.toString().length() > 0);
		System.out.println(sb.toString());
		
		// Try again, this time with a REPLACED_BUY
		sb = new StringBuffer();
		goTermDetail.put(GoUpdateConstants.REPLACED_BY, Arrays.asList("12345"));
		goTerms.put(TEST_GO_ID, goTermDetail);
		modifier.deleteGoInstance(goTerms, allGoInstances , sb);
		assert(sb.toString().length() > 0);
		System.out.println(sb.toString());
		
		// Try again, this time with a ALT_ID
		sb = new StringBuffer();
		goTermDetail.remove(GoUpdateConstants.REPLACED_BY);
		goTermDetail.put(GoUpdateConstants.ALT_ID, Arrays.asList("12345"));
		goTerms.put(TEST_GO_ID, goTermDetail);
		modifier.deleteGoInstance(goTerms, allGoInstances , sb);
		assert(sb.toString().length() > 0);
		System.out.println(sb.toString());

		// Try again, this time with CONSIDER, and a valid replacement object.
		sb = new StringBuffer();
		goTermDetail.remove(GoUpdateConstants.ALT_ID);
		goTermDetail.put(GoUpdateConstants.CONSIDER, Arrays.asList("12345"));
		GKInstance replacementInstance = mock(GKInstance.class);
		GKInstance referrer = mock(GKInstance.class);
		Mockito.when(newGoTerm.getReferers("componentOf")).thenReturn(Arrays.asList(referrer));
		Mockito.when(referrer.getDBID()).thenReturn(1122334455L);
		Mockito.when(referrer.getAttributeValue("componentOf")).thenReturn(newGoTerm);
		Mockito.doNothing().when(referrer).setAttributeValue(anyString(), any(GKInstance.class));
		Mockito.doNothing().when(adaptor).updateInstanceAttribute(any(GKInstance.class), anyString());
		Mockito.doNothing().when(adaptor).deleteInstance(any(GKInstance.class));
		allGoInstances.put("12345", Arrays.asList(replacementInstance));
		modifier.deleteGoInstance(goTerms, allGoInstances , sb);
		assert(sb.toString().length() > 0);
		System.out.println(sb.toString());
	}
	
	@Test
	public void updateGoTermTest() throws Exception
	{
		
		Mockito.when(newGoTerm.getAttributeValue(ReactomeJavaConstants.name)).thenReturn("Test");
		Mockito.when(newGoTerm.getAttributeValue(ReactomeJavaConstants.accession)).thenReturn(TEST_GO_ID);
		Mockito.when(newGoTerm.getAttributeValue(ReactomeJavaConstants.definition)).thenReturn("This is a test go term");
		Mockito.when(newGoTerm.getSchemClass()).thenReturn(molecularFunctionGKSchemaClass).thenReturn(biologicalProcessGKSchemaClass);
		
		Mockito.doNothing().when(adaptor).updateInstanceAttribute(any(GKInstance.class), anyString());
		
		// Set up a goTerms structure and a goToEcNumbers structure
		Map<String, Map<String, Object>> goTerms = new HashMap<String, Map<String,Object>>();
		Map<String,List<String>> goToEcNumbers = new HashMap<String, List<String>>();
		
		Map<String, Object> goTermDetail = new HashMap<String, Object>();
		goTermDetail.put(GoUpdateConstants.NAME, "Test-1");
		goTermDetail.put(GoUpdateConstants.DEF, "This is a test go term");
		
		goTerms.put(TEST_GO_ID, goTermDetail);
		
		goToEcNumbers.put(TEST_GO_ID, Arrays.asList("1.2.3.4"));
		GoTermInstanceModifier modifier = new GoTermInstanceModifier(adaptor, newGoTerm, mockInstanceEdit);
		StringBuffer sb = new StringBuffer();
		try
		{
			
			modifier.updateGOInstance(goTerms, goToEcNumbers, sb);
			modifier.updateGOInstance(goTerms, goToEcNumbers, sb);
			System.out.println(sb.toString());
			assert(sb.toString().length() > 0);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
	}
	
	@Test
	public void createGoTermTest() throws InvalidAttributeException, InvalidAttributeValueException, Exception
	{
		//PowerMockito.whenNew(GKInstance.class).withAnyArguments().thenReturn(newGoTerm);

		// Mock storing any instance
		Mockito.when(adaptor.storeInstance(any(GKInstance.class))).thenReturn(123L);
		
		PowerMockito.mockStatic(InstanceDisplayNameGenerator.class) ;
		
		Mockito.when(mockSchema.getClassByName( anyString() )).thenReturn(biologicalProcessGKSchemaClass).thenReturn(molecularFunctionGKSchemaClass);
		Mockito.when(adaptor.getSchema()).thenReturn(mockSchema);
		
		// Set up a goTerms structure and a goToEcNumbers structure
		Map<String, Map<String, Object>> goTerms = new HashMap<String, Map<String,Object>>();
		Map<String,List<String>> goToEcNumbers = new HashMap<String, List<String>>();
		
		Map<String, Object> goTermDetail = new HashMap<String, Object>();
		goTermDetail.put(GoUpdateConstants.NAME, "Test");
		goTermDetail.put(GoUpdateConstants.DEF, "This is a test go term");
		
		goTerms.put(TEST_GO_ID, goTermDetail);
		
		goToEcNumbers.put(TEST_GO_ID, Arrays.asList("1.2.3.4"));
		GoTermInstanceModifier modifier = new GoTermInstanceModifier(adaptor, mockInstanceEdit);
		try
		{
			modifier.createNewGOTerm(goTerms, goToEcNumbers, TEST_GO_ID, ReactomeJavaConstants.GO_BiologicalProcess, mockRefDB);
			modifier.createNewGOTerm(goTerms, goToEcNumbers, TEST_GO_ID, ReactomeJavaConstants.GO_MolecularFunction, mockRefDB);
			System.out.println(newGoTerm.toString());
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
		
	}
	
}
