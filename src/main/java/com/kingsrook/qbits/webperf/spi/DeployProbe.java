/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.webperf.spi;


import java.io.Serializable;


/*******************************************************************************
 ** Reports the running build of one component (design §6.3, D7). The QBit
 ** polls its probes; a changed value is recorded as a webperf_change_event and
 ** re-tests the KEY pages after a settle delay.
 *******************************************************************************/
public interface DeployProbe extends Serializable
{
   /*******************************************************************************
    ** The component this probe watches, e.g. FRONTEND or BACKEND.
    **
    ** @return the component
    *******************************************************************************/
   String component();



   /*******************************************************************************
    ** The running build id, or null when the component reports none.
    **
    ** @return the build id or null
    ** @throws Exception when the component cannot be reached (nothing is
    **                   recorded; the next poll tries again)
    *******************************************************************************/
   String currentBuildId() throws Exception;
}
