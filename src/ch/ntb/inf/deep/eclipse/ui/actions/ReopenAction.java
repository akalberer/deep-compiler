/*
 * Copyright (c) 2011 NTB Interstate University of Applied Sciences of Technology Buchs.
 *
 * http://www.ntb.ch/inf
 * 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Eclipse Public License for more details.
 * 
 * Contributors:
 *     NTB - initial implementation
 * 
 */

package ch.ntb.inf.deep.eclipse.ui.actions;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWindowActionDelegate;
import ch.ntb.inf.deep.host.ErrorReporter;
import ch.ntb.inf.deep.host.StdStreams;
import ch.ntb.inf.deep.launcher.Launcher;
import ch.ntb.inf.deep.target.TargetConnection;
import ch.ntb.inf.deep.target.TargetConnectionException;

public class ReopenAction implements IWorkbenchWindowActionDelegate {

	private IWorkbenchWindow window;
    public static final String ID = "ch.ntb.inf.deep.eclipse.ui.actions.ReopenAction";
	
	@Override
	public void dispose() {}

	@Override
	public void init(IWorkbenchWindow window) {
		this.window = window;
	}

	@Override
	public void run(IAction action) {
		TargetConnection tc = Launcher.getTargetConnection();
		if(tc == null){
			ErrorReporter.reporter.error(TargetConnection.errTargetNotFound);
			return;
		}
		tc.closeConnection();
		try {
			Thread.sleep(500);//Give OS time 
			tc.openConnection();
			StdStreams.log.println("Device succesfully reopened");
		} catch (TargetConnectionException e) {
			tc.closeConnection();
			ErrorReporter.reporter.error(TargetConnection.errReopenFailed);
		} catch (InterruptedException e) {
		}
	}

	@Override
	public void selectionChanged(IAction action, ISelection selection) {}

}
