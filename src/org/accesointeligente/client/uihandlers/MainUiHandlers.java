/**
 * Acceso Inteligente
 *
 * Copyright (C) 2010-2011 Fundación Ciudadano Inteligente
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.accesointeligente.client.uihandlers;

import com.gwtplatform.mvp.client.UiHandlers;

public interface MainUiHandlers extends UiHandlers {
	void clearNotifications();
	void gotoMyRequests();
	void gotoDrafts();
	void gotoFavorites();
	void gotoProfile();
	void gotoLogout();
	void gotoStatistics();
	void gotoAboutProject();
	void gotoContact();
	void gotoLogin();
	void gotoHome();
	void clearPopupSlot();
}
