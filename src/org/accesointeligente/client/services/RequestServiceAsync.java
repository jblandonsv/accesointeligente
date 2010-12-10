package org.accesointeligente.client.services;

import org.accesointeligente.model.Request;
import org.accesointeligente.model.RequestCategory;

import com.google.gwt.user.client.rpc.AsyncCallback;

import java.util.Set;

public interface RequestServiceAsync {
	void makeRequest(Request request, AsyncCallback<Void> callback);
	void getCategories(AsyncCallback<Set<RequestCategory>> callback);
}