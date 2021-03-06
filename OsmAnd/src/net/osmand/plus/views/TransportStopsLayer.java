package net.osmand.plus.views;

import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import net.osmand.ResultMatcher;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.QuadRect;
import net.osmand.data.QuadTree;
import net.osmand.data.RotatedTileBox;
import net.osmand.data.TransportRoute;
import net.osmand.data.TransportStop;
import net.osmand.osm.edit.Node;
import net.osmand.osm.edit.Way;
import net.osmand.plus.R;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.DisplayMetrics;
import android.view.WindowManager;

public class TransportStopsLayer extends OsmandMapLayer implements ContextMenuLayer.IContextMenuProvider {
	private static final int startZoom = 12;
	
	private OsmandMapTileView view;

	private Paint paintIcon;
	private Bitmap stopBus;
	private Bitmap stopSmall;
	private RenderingLineAttributes attrs;

	private MapLayerData<List<TransportStop>> data;
	private TransportRoute route = null;

	private Path path;

	@SuppressWarnings("deprecation")
	@Override
	public void initLayer(final OsmandMapTileView view) {
		this.view = view;
		DisplayMetrics dm = new DisplayMetrics();
		WindowManager wmgr = (WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE);
		wmgr.getDefaultDisplay().getMetrics(dm);

		paintIcon = new Paint();
		path = new Path();
		stopBus = BitmapFactory.decodeResource(view.getResources(), R.drawable.map_transport_stop_bus);
		stopSmall = BitmapFactory.decodeResource(view.getResources(), R.drawable.map_transport_stop_small);
		attrs = new RenderingLineAttributes("transport_route");
		attrs.defaultWidth = (int) (12 * view.getDensity());
		attrs.defaultColor = view.getResources().getColor(R.color.transport_route_line);
		data = new OsmandMapLayer.MapLayerData<List<TransportStop>>() {
			{
				ZOOM_THRESHOLD = 0;
			}

			@Override
			public boolean isInterrupted() {
				return super.isInterrupted();
			}

			@Override
			public void layerOnPostExecute() {
				view.refreshMap();
			}

			@Override
			protected List<TransportStop> calculateResult(RotatedTileBox tileBox) {
				QuadRect latLonBounds = tileBox.getLatLonBounds();
				if (latLonBounds == null) {
					return new ArrayList<>();
				}
				List<TransportStop> res = view.getApplication().getResourceManager().searchTransportSync(latLonBounds.top, latLonBounds.left,
						latLonBounds.bottom, latLonBounds.right, new ResultMatcher<TransportStop>() {

							@Override
							public boolean publish(TransportStop object) {
								return true;
							}

							@Override
							public boolean isCancelled() {
								return isInterrupted();
							}
						});
				Collections.sort(res, new Comparator<TransportStop>() {
					@Override
					public int compare(TransportStop lhs, TransportStop rhs) {
						return lhs.getId() < rhs.getId() ? -1 : (lhs.getId().longValue() == rhs.getId().longValue() ? 0 : 1);
					}
				});

				return res;
			}
		};
	}
	
	public void getFromPoint(RotatedTileBox tb,PointF point, List<? super TransportStop> res) {
		if (data.getResults() != null) {
			List<TransportStop> objects = route != null ? route.getForwardStops() : data.getResults();
			int ex = (int) point.x;
			int ey = (int) point.y;
			final int rp = getRadiusPoi(tb);
			int radius = rp * 3 / 2;
			try {
				TreeSet<String> ms = new TreeSet<>();
				for (int i = 0; i < objects.size(); i++) {
					TransportStop n = objects.get(i);
					if (n.getLocation() == null){
						continue;
					}
					int x = (int) tb.getPixXFromLatLon(n.getLocation().getLatitude(), n.getLocation().getLongitude());
					int y = (int) tb.getPixYFromLatLon(n.getLocation().getLatitude(), n.getLocation().getLongitude());
					if (Math.abs(x - ex) <= radius && Math.abs(y - ey) <= radius) {
						if(!ms.add(n.getName())) {
							// only unique names
							continue;
						}
						radius = rp;
						res.add(n);
					}
				}
			} catch (IndexOutOfBoundsException e) {
				// that's really rare case, but is much efficient than introduce synchronized block
			}
		}
	}
	
	public TransportRoute getRoute() {
		return route;
	}
	
	public void setRoute(TransportRoute route) {
		this.route = route;
	}
	

	
	public int getRadiusPoi(RotatedTileBox tb){
		final double zoom = tb.getZoom();
		int r;
		if(zoom < startZoom){
			r = 0;
		} else if(zoom <= 15){
			r = 8;
		} else if(zoom <= 16){
			r = 10;
		} else if(zoom <= 17){
			r = 14;
		} else {
			r = 18;
		}
		return (int) (r * tb.getDensity());
	}

	
	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tb,
			DrawSettings settings) {
		if (tb.getZoom() >= startZoom) {
			data.queryNewData(tb);
			if(route != null) {
				attrs.updatePaints(view, settings, tb);
				canvas.rotate(-tb.getRotate(), tb.getCenterPixelX(), tb.getCenterPixelY());
				try {
					path.reset();
					List<Way> ws = route.getForwardWays();
					if (ws != null) {
						for (Way w : ws) {
							TIntArrayList tx = new TIntArrayList();
							TIntArrayList ty = new TIntArrayList();
							for (int i = 0; i < w.getNodes().size(); i++) {
								Node o = w.getNodes().get(i);
								int x = (int) tb.getPixXFromLatLon(o.getLatitude(), o.getLongitude());
								int y = (int) tb.getPixYFromLatLon(o.getLatitude(), o.getLongitude());
								tx.add(x);
								ty.add(y);
							}
							calculatePath(tb, tx, ty, path);
						}
					}
					attrs.drawPath(canvas, path);
				} finally {
					canvas.rotate(tb.getRotate(), tb.getCenterPixelX(), tb.getCenterPixelY());
				}
			}
			
			float iconSize = stopBus.getWidth() * 3 / 2.5f;
			QuadTree<QuadRect> boundIntersections = initBoundIntersections(tb);
			List<TransportStop> fullObjects = new ArrayList<>();
			List<TransportStop> objects = data.getResults() ;
			if(route != null) {
				objects = route.getForwardStops();
			}
			if (objects != null) {
				for (TransportStop o : objects) {
					float x = tb.getPixXFromLatLon(o.getLocation().getLatitude(), o.getLocation().getLongitude());
					float y = tb.getPixYFromLatLon(o.getLocation().getLatitude(), o.getLocation().getLongitude());

					if (intersects(boundIntersections, x, y, iconSize, iconSize)) {
						canvas.drawBitmap(stopSmall, x - stopSmall.getWidth() / 2, y - stopSmall.getHeight() / 2,
								paintIcon);
					} else {
						fullObjects.add(o);
					}
				}
				for (TransportStop o : fullObjects) {
					float x = tb.getPixXFromLatLon(o.getLocation().getLatitude(), o.getLocation().getLongitude());
					float y = tb.getPixYFromLatLon(o.getLocation().getLatitude(), o.getLocation().getLongitude());
					Bitmap b = stopBus;
					canvas.drawBitmap(b, x - b.getWidth() / 2, y - b.getHeight() / 2, paintIcon);
				}
			}
			
		}
	}
	
	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tb, DrawSettings settings) {
	}

	@Override
	public void destroyLayer() {
	}

	@Override
	public boolean drawInScreenPixels() {
		return true;
	}

	@Override
	public boolean onLongPressEvent(PointF point, RotatedTileBox tileBox) {
		return false;
	}

	@Override
	public PointDescription getObjectName(Object o) {
		if(o instanceof TransportStop){
			return new PointDescription(PointDescription.POINT_TYPE_TRANSPORT_STOP, view.getContext().getString(R.string.transport_Stop),
					((TransportStop)o).getName()); 
		}
		return null;
	}

	@Override
	public boolean disableSingleTap() {
		return false;
	}

	@Override
	public boolean disableLongPressOnMap() {
		return false;
	}

	@Override
	public boolean isObjectClickable(Object o) {
		return false;
	}

	@Override
	public void collectObjectsFromPoint(PointF point, RotatedTileBox tileBox, List<Object> res) {
		if (tileBox.getZoom() >= startZoom) {
			getFromPoint(tileBox, point, res);
		}
	}

	@Override
	public LatLon getObjectLocation(Object o) {
		if(o instanceof TransportStop){
			return ((TransportStop)o).getLocation();
		}
		return null;
	}

}
