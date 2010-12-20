/*******************************************************************************
 * Copyright (c) 2005, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Bjorn Freeman-Benson - initial API and implementation
 *     
 * changes: Millischer Roger  adapt from Example  PDAMainTab
 *******************************************************************************/
package ch.ntb.inf.deep.ui.tabs;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.AbstractLaunchConfigurationTab;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.ResourceListSelectionDialog;

import ch.ntb.inf.deep.DeepPlugin;

/**
 * Tab to specify the Deep program to run/debug.
 */
public class DeepMainTab extends AbstractLaunchConfigurationTab {

	private Text fProgramText;
	private String locationPath;
	private Button fProgramButton;

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.debug.ui.ILaunchConfigurationTab#createControl(org.eclipse
	 * .swt.widgets.Composite)
	 */
	public void createControl(Composite parent) {
		Font font = parent.getFont();

		Composite comp = new Composite(parent, SWT.NONE);
		setControl(comp);
		GridLayout topLayout = new GridLayout();
		topLayout.verticalSpacing = 0;
		topLayout.numColumns = 3;
		comp.setLayout(topLayout);
		comp.setFont(font);

		createVerticalSpacer(comp, 3);

		Label programLabel = new Label(comp, SWT.NONE);
		programLabel.setText("&Program:");
		GridData gd = new GridData(GridData.BEGINNING);
		programLabel.setLayoutData(gd);
		programLabel.setFont(font);

		fProgramText = new Text(comp, SWT.SINGLE | SWT.BORDER);
		gd = new GridData(GridData.FILL_HORIZONTAL);
		fProgramText.setLayoutData(gd);
		fProgramText.setFont(font);
		fProgramText.addModifyListener(new ModifyListener() {
			public void modifyText(ModifyEvent e) {
				updateLaunchConfigurationDialog();
			}
		});

		fProgramButton = createPushButton(comp, "&Add...", null); //$NON-NLS-1$
		fProgramButton.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				browseFiles();
			}
		});
	}

	/**
	 * Open a resource chooser to select a program
	 */
	protected void browseFiles() {
		ResourceListSelectionDialog dialog = new ResourceListSelectionDialog(
				getShell(), ResourcesPlugin.getWorkspace().getRoot(),
				IResource.FILE);
		dialog.setTitle("Deep Program");
		dialog.setMessage("Select Deep Program");
		if (dialog.open() == Window.OK) {
			Object[] files = dialog.getResult();
			IFile file = (IFile) files[0];
			locationPath = file.getProject().getLocation().toString();
			String temp = fProgramText.getText();
			if (!temp.equals("")) {
				temp = temp + ";";
			}
			fProgramText.setText(temp + file.getFullPath().toString());
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.debug.ui.ILaunchConfigurationTab#setDefaults(org.eclipse.
	 * debug.core.ILaunchConfigurationWorkingCopy)
	 */
	public void setDefaults(ILaunchConfigurationWorkingCopy configuration) {
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.debug.ui.ILaunchConfigurationTab#initializeFrom(org.eclipse
	 * .debug.core.ILaunchConfiguration)
	 */
	public void initializeFrom(ILaunchConfiguration configuration) {
		try {
			String program = null;
			program = configuration.getAttribute(DeepPlugin.ATTR_DEEP_PROGRAM,
					(String) null);
			if (program != null) {
				fProgramText.setText(program);
			}
			locationPath = configuration.getAttribute(
					DeepPlugin.ATTR_DEEP_LOCATION, "");
		} catch (CoreException e) {
			setErrorMessage(e.getMessage());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.debug.ui.ILaunchConfigurationTab#performApply(org.eclipse
	 * .debug.core.ILaunchConfigurationWorkingCopy)
	 */
	public void performApply(ILaunchConfigurationWorkingCopy configuration) {
		String program = fProgramText.getText().trim();
		if (program.length() == 0) {
			program = null;
		}
		configuration.setAttribute(DeepPlugin.ATTR_DEEP_PROGRAM, program);
		configuration.setAttribute(DeepPlugin.ATTR_DEEP_LOCATION, locationPath);

		// perform resource mapping for contextual launch
		IResource[] resources = null;
		if (program != null) {
			IPath path = new Path(program);
			IResource res = ResourcesPlugin.getWorkspace().getRoot()
					.findMember(path);
			if (res != null) {
				resources = new IResource[] { res };
			}
		}
		configuration.setMappedResources(resources);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.ui.ILaunchConfigurationTab#getName()
	 */
	public String getName() {
		return "Main";
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.debug.ui.ILaunchConfigurationTab#isValid(org.eclipse.debug
	 * .core.ILaunchConfiguration)
	 */
	public boolean isValid(ILaunchConfiguration launchConfig) {
		setErrorMessage(null);
		setMessage(null);
		String text = fProgramText.getText();
		if (text.length() > 0) {
			String[] files = text.split(";");
			for (int i = 0; i < files.length; i++) {
				IPath path = new Path(files[i]);
				if (ResourcesPlugin.getWorkspace().getRoot().findMember(path) == null) {
					setErrorMessage(files[i] + " does not exist");
					return false;
				}
			}
		} else {
			setMessage("Specify a program");
		}
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.ui.ILaunchConfigurationTab#getImage()
	 */
	public Image getImage() {
		return null; // DebugUIPlugin.getDefault().getImageRegistry().get(DebugUIPlugin.IMG_OBJ_PDA);
	}
}
