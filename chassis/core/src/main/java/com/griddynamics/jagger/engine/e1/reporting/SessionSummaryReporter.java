/*
 * Copyright (c) 2010-2012 Grid Dynamics Consulting Services, Inc, All Rights Reserved
 * http://www.griddynamics.com
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of
 * the GNU Lesser General Public License as published by the Free Software Foundation; either
 * version 2.1 of the License, or any later version.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.griddynamics.jagger.engine.e1.reporting;

import java.util.List;

import com.griddynamics.jagger.engine.e1.aggregator.session.model.SessionData;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;

import org.springframework.orm.hibernate3.support.HibernateDaoSupport;

import com.griddynamics.jagger.master.SessionIdProvider;
import com.griddynamics.jagger.reporting.ReportProvider;
import com.griddynamics.jagger.reporting.ReportingContext;

/**
 * Provides data for session summary report.
 * 
 * @author Mairbek Khadikov
 */
public class SessionSummaryReporter extends HibernateDaoSupport implements ReportProvider {
	private SessionIdProvider sessionIdProvider;
	private ReportingContext context;
	
	private String template;

	public SessionIdProvider getSessionIdProvider() {
		return sessionIdProvider;
	}

	public void setSessionIdProvider(SessionIdProvider sessionIdProvider) {
		this.sessionIdProvider = sessionIdProvider;
	}

	public ReportingContext getContext() {
		return context;
	}

	@Override
	public JRDataSource getDataSource() {
		@SuppressWarnings("unchecked")
		List<SessionData> all = getHibernateTemplate().find("from SessionData sd where sd.sessionId=?",
				sessionIdProvider.getSessionId());

		if (all.size() > 1) {
			throw new IllegalStateException("To much session data was stored for the session.");
		}
		if (all.isEmpty()) {
			throw new IllegalStateException("Data was not stored");
		}
        all.get(0).setSessionName(sessionIdProvider.getSessionName());

		return new JRBeanCollectionDataSource(all);
	}

	@Override
	public JasperReport getReport() {
		return context.getReport(template);
	}

	@Override
	public void setContext(ReportingContext context) {
		this.context = context;
	}

	public void setTemplate(String template) {
		this.template = template;
	}

}
