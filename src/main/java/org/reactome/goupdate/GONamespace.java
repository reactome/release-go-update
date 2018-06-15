package org.reactome.goupdate;

import org.gk.model.ReactomeJavaConstants;

/**
 * Represents the namespaces that may be found in the GO file.
 * @author sshorser
 *
 */
public enum GONamespace
{
	biological_process, molecular_function, cellular_component;
	
	/**
	 * Provides the Reactome schema class that corresponds to this GO namespace.
	 * @return
	 */
	public String getReactomeName()
	{
		if (this.name().equals(biological_process.name()))
		{
			return ReactomeJavaConstants.GO_BiologicalProcess;
		}
		else if(this.name().equals(molecular_function.name()))
		{
			return ReactomeJavaConstants.GO_MolecularFunction;
		}
		else if(this.name().equals(cellular_component.name()))
		{
			return ReactomeJavaConstants.GO_CellularComponent;
		}
		else
		{
			return null;
		}
	}
}
