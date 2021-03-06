package com.multiprocess.crossprocess;

import java.io.FileDescriptor;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.multiprocess.AppEnv;
import com.zero.App;

public class ServiceManager {

	private static final boolean DEBUG = AppEnv.DEBUG;

	private static final String TAG = ServiceManager.class.getSimpleName();

	private static final Uri SERVICE_MANAGER_URI;

	static final String SERVICE_MANAGER_KEY = ServiceManagerProvider.PATH_SERVICE_PROVIDER;

	static {
		SERVICE_MANAGER_URI = Uri.parse("content://"
				+ ServiceManagerProvider.AUTHORITY + "/"
				+ ServiceManagerProvider.PATH_SERVICE_PROVIDER);
	}

	private static ServiceManagerWrapper sServiceManagerWrapper = new ServiceManagerWrapper();

	static ServiceManagerWrapper getServiceManagerImpl() {
		return sServiceManagerWrapper;
	}

	private static class ServiceManagerWrapper implements
			IServiceManagerService, IBinder.DeathRecipient {
		private IServiceManagerService mServerManagerImpl;

		final IServiceManagerService getServerManagerService() {
			IServiceManagerService service = mServerManagerImpl;
			if (service != null) {
				return service;
			}
			return queryService();
		}

		/**
		 * 通过ServiceManagerProvider获取服务管理对象
		 * 
		 * @return
		 */
		private synchronized IServiceManagerService queryService() {
			IServiceManagerService service = mServerManagerImpl;
			if (service != null) {
				return service;
			}

			Context context = App.getContext();

			Cursor cursor = null;
			try {
				cursor = context.getContentResolver().query(
						SERVICE_MANAGER_URI, null, null, null, null);
				Bundle bundle = cursor.getExtras();
				bundle.setClassLoader(ServiceParcel.class.getClassLoader());
				ServiceParcel serviceParcel = bundle
						.getParcelable(SERVICE_MANAGER_KEY);
				IBinder binder = serviceParcel.getBinder();
				service = IServiceManagerService.Stub.asInterface(binder);
			} catch (Exception e) {
				if (DEBUG) {
					Log.e(TAG, "", e);
				}
			} finally {
				if (cursor != null) {
					try {
						cursor.close();
					} catch (Exception e) {
					}
				}
			}

			if (service != null) {
				mServerManagerImpl = service;
				try {
					service.asBinder().linkToDeath(this, 0);
				} catch (RemoteException e) {
					if (DEBUG) {
						Log.e(TAG, "[queryService]：RemoteException", e);
					}
				}
			}
			return service;
		}

		/**
		 * 返回的是传过来的原始binder对象
		 * 
		 * @param serviceId
		 * @return
		 * @throws RemoteException
		 */
		IBinder getOriginalService(int serviceId) throws RemoteException {
			IServiceManagerService service = getServerManagerService();
			if (service != null) {
				IBinder binder = service.getService(serviceId);
				return binder;
			}
			return null;
		}

		@Override
		public IBinder asBinder() {
			IServiceManagerService serverChannel = getServerManagerService();
			if (serverChannel != null) {
				return serverChannel.asBinder();
			}
			return null;
		}

		/**
		 * 返回的binder对象会包一层wrapper
		 * 
		 */
		@Override
		public IBinder getService(int serviceId) throws RemoteException {
			IServiceManagerService service = getServerManagerService();
			if (service != null) {
				IBinder binder = service.getService(serviceId);
				if (binder != null) {
					return RemoteBinderWrapper.getService(serviceId, binder);
				}
			}
			return null;
		}

		@Override
		public void binderDied() {
			if (DEBUG) {
				Log.d(TAG, "[binderDied] service channel died");
			}

			/*
			 * ServerProcessDiedListener serverProcessDiedListener =
			 * sServerProcessDiedListener; if (serverProcessDiedListener == null
			 * || serverProcessDiedListener.onServerProcessDied()) {
			 * synchronized (this) { mServerChannel = null;
			 * connectToServerChannel(); } }
			 */
		}
	}

	/**
	 * 
	 * 传过来的Binder对象的Wrapper，防止binder传进来为空
	 * 
	 * @author chaopei
	 *
	 */
	private static class RemoteBinderWrapper implements IBinder,
			IBinder.DeathRecipient {

		private IBinder mRemoteBinderImpl;
		private int mServiceId;

		public static IBinder getService(int serviceId, IBinder binder) {

			String descriptor = null;
			try {
				descriptor = binder.getInterfaceDescriptor();
			} catch (RemoteException e) {
			}
			android.os.IInterface iin = binder.queryLocalInterface(descriptor);
			if (((iin != null) && App.runInServerProcess())) {
				return binder;
			}
			return new RemoteBinderWrapper(serviceId, binder);
		}

		private RemoteBinderWrapper(int id, IBinder binder) {
			mRemoteBinderImpl = binder;
			mServiceId = id;
			try {
				mRemoteBinderImpl.linkToDeath(this, 0);
			} catch (RemoteException e) {
				if (DEBUG) {
					Log.e(TAG,
							"[ModuleChannelWrapper constructor]：RemoteException",
							e);
				}
			}
		}

		private IBinder getRemoteBinder() throws RemoteException {
			IBinder remote = mRemoteBinderImpl;
			if (remote != null) {
				return remote;
			}
			ServiceManagerWrapper serverChannel = (ServiceManagerWrapper) getServiceManagerImpl();
			remote = serverChannel.getOriginalService(mServiceId);
			if (remote == null) {
				throw new RemoteException();
			}
			return remote;
		}

		@Override
		public String getInterfaceDescriptor() throws RemoteException {
			return getRemoteBinder().getInterfaceDescriptor();
		}

		@Override
		public boolean pingBinder() {
			try {
				return getRemoteBinder().pingBinder();
			} catch (RemoteException e) {

			}
			return false;
		}

		@Override
		public boolean isBinderAlive() {
			try {
				return getRemoteBinder().isBinderAlive();
			} catch (RemoteException e) {
			}
			return false;
		}

		@Override
		public IInterface queryLocalInterface(String descriptor) {
			try {
				return getRemoteBinder().queryLocalInterface(descriptor);
			} catch (RemoteException e) {
			}
			return null;
		}

		@Override
		public void dump(FileDescriptor fd, String[] args)
				throws RemoteException {
			getRemoteBinder().dump(fd, args);
		}

		@Override
		public boolean transact(int code, Parcel data, Parcel reply, int flags)
				throws RemoteException {
			return getRemoteBinder().transact(code, data, reply, flags);
		}

		@Override
		public void linkToDeath(DeathRecipient recipient, int flags)
				throws RemoteException {
			getRemoteBinder().linkToDeath(recipient, flags);
		}

		@Override
		public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
			try {
				return getRemoteBinder().unlinkToDeath(recipient, flags);
			} catch (Exception e) {
			}
			return false;
		}

		@Override
		public void binderDied() {
			if (DEBUG) {
				Log.d(TAG, "[binderDied]");
			}
			mRemoteBinderImpl = null;
		}

        public void dumpAsync(FileDescriptor fd, String[] args)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "[dumpAsync]");
            }
        }

	}

	/**
	 * 拿到IBinder后直接转接口，外部一律用此接口
	 * 
	 * @param id
	 * @return
	 */
	public static IInterface getService(int id) {
		IBinder binder = ServiceList.getCacheBinder(id);
		if (null == binder) {
			if (DEBUG) {
				Log.d(TAG, "[getService]：binder has no cache");
			}
			ServiceManagerWrapper serviceManagerWrapper = getServiceManagerImpl();
			if (serviceManagerWrapper != null) {
				try {
					binder = serviceManagerWrapper.getService(id);
				} catch (RemoteException e) {
					if (DEBUG) {
						Log.e(TAG, "[getService]：RemoteException", e);
					}
				}
			}
			if (null!=binder) {
				ServiceList.putCacheBinder(id, binder);
			}
		}else {
			if (DEBUG) {
				Log.d(TAG, "[getService]：binder has cache");
			}
		}
		if (null == binder) {
			if (DEBUG) {
				Log.e(TAG, "[getService]：binder is null");
			}
			return null;
		}
		if (DEBUG) {
			Log.d(TAG, "[getService]：binder is not null");
		}
		return ServiceList.getInterface(id,binder);
	}
}
