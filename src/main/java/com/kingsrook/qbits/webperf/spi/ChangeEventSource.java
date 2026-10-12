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
import java.time.Instant;
import java.util.List;


/*******************************************************************************
 ** Reports changes that may move performance and are owned by another QBit,
 ** e.g. CDN hostname stage changes from qbit-cdn (design §6.5). They become
 ** webperf_change_event rows, drawn as markers on trends.
 *******************************************************************************/
public interface ChangeEventSource extends Serializable
{
   /*******************************************************************************
    ** One change.
    **
    ** @param kind       e.g. CDN_STAGE
    ** @param component  e.g. the hostname
    ** @param fromValue  the previous value (may be null)
    ** @param toValue    the new value
    ** @param observedAt when it happened
    *******************************************************************************/
   record Change(String kind, String component, String fromValue, String toValue, Instant observedAt) implements Serializable
   {
   }



   /*******************************************************************************
    ** Changes since an instant, oldest first.
    **
    ** @param since the instant
    ** @return the changes
    ** @throws Exception on a read failure
    *******************************************************************************/
   List<Change> changesSince(Instant since) throws Exception;
}
