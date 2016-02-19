/**
 * Copyright 2015 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import play.Logger;
import play.libs.Json;

public class FlowsDAO extends AbstractMySQLOpenSourceDAO
{
	private final static String GET_APP_ID  =
			"SELECT app_id FROM cfg_application WHERE LOWER(app_code) = ?";

	private final static String GET_PAGED_PROJECTS_BY_APP_ID = "SELECT SQL_CALC_FOUND_ROWS " +
			"distinct IFNULL(flow_group, 'NA') as project_name, flow_group " +
			"from flow WHERE app_id = ? GROUP BY 1 limit ?, ?";

	private final static String GET_FLOW_COUNT_BY_APP_ID_AND_PROJECT_NAME = "SELECT count(*) " +
			"FROM flow WHERE app_id = ? and flow_group = ?";

	private final static String GET_FLOW_COUNT_WITHOUT_PROJECT_BY_APP_ID = "SELECT count(*) " +
			"FROM flow WHERE app_id = ? and flow_group is null";

	private final static String GET_PAGED_FLOWS_BY_APP_ID_AND_PROJECT_NAME = "SELECT SQL_CALC_FOUND_ROWS " +
			"DISTINCT flow_id, flow_name, flow_path, flow_level, " +
            "FROM_UNIXTIME(created_time) as created_time, FROM_UNIXTIME(modified_time) as modified_time FROM flow " +
			"WHERE app_id = ? and flow_group = ? and (is_active is null or is_active = 'Y') ORDER BY 1 LIMIT ?, ?";

	private final static String GET_PAGED_FLOWS_WITHOUT_PROJECT_BY_APP_ID = "SELECT SQL_CALC_FOUND_ROWS " +
			"DISTINCT flow_id, flow_name, flow_path, flow_level, " +
            "FROM_UNIXTIME(created_time) as created_time, FROM_UNIXTIME(modified_time) as modified_time FROM flow " +
			"WHERE app_id = ? and flow_group is null and (is_active is null or is_active = 'Y') ORDER BY 1 LIMIT ?, ?";

	private final static String GET_JOB_COUNT_BY_APP_ID_AND_FLOW_ID =
			"SELECT count(*) FROM flow_job WHERE app_id = ? and flow_id = ?";

	private final static String GET_PAGED_JOBS_BY_APP_ID_AND_FLOW_ID = "select SQL_CALC_FOUND_ROWS " +
			"j.job_id, MAX(j.last_source_version), j.job_name, j.job_path, j.job_type, FROM_UNIXTIME(j.created_time) as created_time, " +
			"FROM_UNIXTIME(j.modified_time) as modified_time, f.flow_name " +
			"FROM flow_job j JOIN flow f on j.app_id = f.app_id and j.flow_id = f.flow_id " +
			"WHERE j.app_id = ? and j.flow_id = ? GROUP BY j.job_id, j.job_name, j.job_path, j.job_type, " +
			"f.flow_name ORDER BY j.job_id LIMIT ?, ?";

	public static Integer getApplicationIDByName(String applicationName)
	{
		Integer applicationId = 0;
		try {
			applicationId = getJdbcTemplate().queryForObject(
					GET_APP_ID,
					new Object[]{applicationName.replace(".", " ")},
					Integer.class);
		} catch (EmptyResultDataAccessException e) {
			applicationId = 0;
			Logger.error("Get application id failed, application name = " + applicationName);
			Logger.error("Exception = " + e.getMessage());
		}

		return applicationId;
	}

	public static ObjectNode getPagedProjectsByApplication(String applicationName, int page, int size)
	{
		String application = applicationName.replace(".", " ");
		ObjectNode result = Json.newObject();

		Integer appID = getApplicationIDByName(applicationName);

		if (appID != 0) {
			javax.sql.DataSource ds = getJdbcTemplate().getDataSource();
			DataSourceTransactionManager tm = new DataSourceTransactionManager(ds);
			TransactionTemplate txTemplate = new TransactionTemplate(tm);
			final int applicationID = appID;

			result = txTemplate.execute(new TransactionCallback<ObjectNode>() {
				public ObjectNode doInTransaction(TransactionStatus status) {

					ObjectNode resultNode = Json.newObject();
					List<Project> pagedProjects = getJdbcTemplate().query(
						GET_PAGED_PROJECTS_BY_APP_ID,
						new ProjectRowMapper(),
						applicationID,
						(page - 1) * size, size);
					long count = 0;
					try {
						count = getJdbcTemplate().queryForObject(
								"SELECT FOUND_ROWS()",
								Long.class);
					} catch (EmptyResultDataAccessException e) {
						Logger.error("Exception = " + e.getMessage());
					}
					if (pagedProjects != null && pagedProjects.size() > 0)
					{
						for(Project project : pagedProjects)
						{
							Long flowCount = 0L;
							if (StringUtils.isNotBlank(project.flowGroup))
							{
								try {
									flowCount = getJdbcTemplate().queryForObject(
											GET_FLOW_COUNT_BY_APP_ID_AND_PROJECT_NAME,
											new Object[] {appID, project.name},
											Long.class);
									project.flowCount = flowCount;
								}
								catch(EmptyResultDataAccessException e)
								{
									Logger.error("Exception = " + e.getMessage());
								}
							}
							else
							{
								try {
									flowCount = getJdbcTemplate().queryForObject(
											GET_FLOW_COUNT_WITHOUT_PROJECT_BY_APP_ID,
											new Object[] {appID},
											Long.class);
									project.flowCount = flowCount;
								}
								catch(EmptyResultDataAccessException e)
								{
									Logger.error("Exception = " + e.getMessage());
								}
							}
						}
					}
					resultNode.set("projects", Json.toJson(pagedProjects));

					resultNode.put("count", count);
					resultNode.put("page", page);
					resultNode.put("itemsPerPage", size);
					resultNode.put("totalPages", (int) Math.ceil(count / ((double) size)));


					return resultNode;
				}
			});
			return result;
		}
		result = Json.newObject();
		result.put("count", 0);
		result.put("page", page);
		result.put("itemsPerPage", size);
		result.put("totalPages", 0);
		result.set("projects", Json.toJson(""));
		return result;
	}

	public static ObjectNode getPagedFlowsByProject(String applicationName, String project, int page, int size)
	{
		ObjectNode result;

		if (StringUtils.isBlank(applicationName) || StringUtils.isBlank(project))
		{
			result = Json.newObject();
			result.put("count", 0);
			result.put("page", page);
			result.put("itemsPerPage", size);
			result.put("totalPages", 0);
			result.set("flows", Json.toJson(""));
			return result;
		}

		String application = applicationName.replace(".", " ");

		Integer appID = getApplicationIDByName(applicationName);
		if (appID != 0)
		{
			javax.sql.DataSource ds = getJdbcTemplate().getDataSource();
			DataSourceTransactionManager tm = new DataSourceTransactionManager(ds);
			TransactionTemplate txTemplate = new TransactionTemplate(tm);
			final int applicationID = appID;
			result = txTemplate.execute(new TransactionCallback<ObjectNode>()
			{
				public ObjectNode doInTransaction(TransactionStatus status)
				{
					ObjectNode resultNode = Json.newObject();
					long count = 0;
					List<Flow> pagedFlows = new ArrayList<Flow>();
					List<Map<String, Object>> rows = null;
					if (StringUtils.isNotBlank(project) && (!project.equalsIgnoreCase("na")))
					{
						rows = getJdbcTemplate().queryForList(
								GET_PAGED_FLOWS_BY_APP_ID_AND_PROJECT_NAME,
								applicationID,
								project,
								(page - 1) * size,
								size);
					}
					else
					{
						rows = getJdbcTemplate().queryForList(
								GET_PAGED_FLOWS_WITHOUT_PROJECT_BY_APP_ID,
								applicationID,
								(page - 1) * size,
								size);
					}

                    try {
						count = getJdbcTemplate().queryForObject(
								"SELECT FOUND_ROWS()",
								Long.class);
					}
					catch(EmptyResultDataAccessException e)
					{
						Logger.error("Exception = " + e.getMessage());
					}
					for (Map row : rows) {
                    	Flow flow = new Flow();
						flow.id = (Long)row.get("flow_id");
						flow.level = (Integer)row.get("flow_level");
						flow.name = (String)row.get("flow_name");
						flow.path = (String)row.get("flow_path");
						Object created = row.get("created_time");
						if (created != null)
						{
							flow.created = created.toString();
						}
						Object modified = row.get("modified_time");
						if (modified != null)
						{
							flow.modified = row.get("modified_time").toString();
						}

						int jobCount = 0;

						if (flow.id != null && flow.id != 0)
						{
							try {
								jobCount = getJdbcTemplate().queryForObject(
										GET_JOB_COUNT_BY_APP_ID_AND_FLOW_ID,
										new Object[] {appID, flow.id},
										Integer.class);
								flow.jobCount = jobCount;
							}
							catch(EmptyResultDataAccessException e)
							{
								Logger.error("Exception = " + e.getMessage());
							}
                    	}
						pagedFlows.add(flow);
					}
					resultNode.set("flows", Json.toJson(pagedFlows));
					resultNode.put("count", count);
					resultNode.put("page", page);
					resultNode.put("itemsPerPage", size);
					resultNode.put("totalPages", (int)Math.ceil(count/((double)size)));

					return resultNode;
				}
			});
			return result;
		}

		result = Json.newObject();
		result.put("count", 0);
		result.put("page", page);
		result.put("itemsPerPage", size);
		result.put("totalPages", 0);
		result.set("flows", Json.toJson(""));
		return result;
	}

	public static ObjectNode getPagedJobsByFlow(
			String applicationName,
			String project,
			Long flowId,
			int page,
			int size)
	{
		ObjectNode result;
		List<Job> pagedJobs = new ArrayList<Job>();
		int flowSK = 0;

		if (StringUtils.isBlank(applicationName) || StringUtils.isBlank(project) || (flowId <= 0))
		{
			result = Json.newObject();
			result.put("count", 0);
			result.put("page", page);
			result.put("itemsPerPage", size);
			result.put("totalPages", 0);
			result.set("jobs", Json.toJson(""));
			return result;
		}

		String application = applicationName.replace(".", " ");

		Integer appID = getApplicationIDByName(application);
		if (appID != 0)
		{
			javax.sql.DataSource ds = getJdbcTemplate().getDataSource();
			DataSourceTransactionManager tm = new DataSourceTransactionManager(ds);
			TransactionTemplate txTemplate = new TransactionTemplate(tm);
			final long azkabanFlowId = flowId;
			result = txTemplate.execute(new TransactionCallback<ObjectNode>()
			{
				public ObjectNode doInTransaction(TransactionStatus status)
				{
					List<Map<String, Object>> rows = null;
					rows = getJdbcTemplate().queryForList(
							GET_PAGED_JOBS_BY_APP_ID_AND_FLOW_ID,
							appID,
							azkabanFlowId,
							(page - 1) * size,
							size);
					long count = 0;
					String flowName = "";
					try {
						count = getJdbcTemplate().queryForObject(
								"SELECT FOUND_ROWS()",
								Long.class);
					}
					catch(EmptyResultDataAccessException e)
					{
						Logger.error("Exception = " + e.getMessage());
					}
					for (Map row : rows)
					{
            			Job job = new Job();
						job.id = (Long)row.get("job_id");
						job.name = (String)row.get("job_name");
						job.path = (String)row.get("job_path");
						job.type = (String)row.get("job_type");
						Object created = row.get("created_time");
						if (created != null)
						{
							job.created = created.toString();
						}
						Object modified = row.get("modified_time");
						if (modified != null)
						{
							job.modified = modified.toString();
						}

						if (StringUtils.isBlank(flowName))
						{
							flowName = (String)row.get("flow_name");
						}
						pagedJobs.add(job);
					}
					ObjectNode resultNode = Json.newObject();
					resultNode.put("count", count);
					resultNode.put("flow", flowName);
					resultNode.put("page", page);
					resultNode.put("itemsPerPage", size);
					resultNode.put("totalPages", (int)Math.ceil(count/((double)size)));
					resultNode.set("jobs", Json.toJson(pagedJobs));
					return resultNode;
				}
			});
			return result;
		}

		result = Json.newObject();
		result.put("count", 0);
		result.put("page", page);
		result.put("itemsPerPage", size);
		result.put("totalPages", 0);
		result.set("jobs", Json.toJson(""));
		return result;
	}
}
