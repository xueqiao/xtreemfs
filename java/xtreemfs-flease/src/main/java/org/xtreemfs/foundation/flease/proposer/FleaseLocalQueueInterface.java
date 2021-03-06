/*
 * Copyright (c) 2009-2011 by Bjoern Kolbeck, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.foundation.flease.proposer;

import org.xtreemfs.foundation.flease.comm.FleaseMessage;

/**
 *
 * @author bjko
 */
public interface FleaseLocalQueueInterface {

    public void enqueueMessage(FleaseMessage message);

}
